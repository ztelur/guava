/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.util.concurrent;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.math.LongMath;

import java.util.concurrent.TimeUnit;

abstract class SmoothRateLimiter extends RateLimiter {
  /*
   * How is the RateLimiter designed, and why?
   *
   * The primary feature of a RateLimiter is its "stable rate", the maximum rate that
   * is should allow at normal conditions. This is enforced by "throttling" incoming
   * requests as needed, i.e. compute, for an incoming request, the appropriate throttle time,
   * and make the calling thread wait as much.
   *
   * The simplest way to maintain a rate of QPS is to keep the timestamp of the last
   * granted request, and ensure that (1/QPS) seconds have elapsed since then. For example,
   * for a rate of QPS=5 (5 tokens per second), if we ensure that a request isn't granted
   * earlier than 200ms after the last one, then we achieve the intended rate.
   * If a request comes and the last request was granted only 100ms ago, then we wait for
   * another 100ms. At this rate, serving 15 fresh permits (i.e. for an acquire(15) request)
   * naturally takes 3 seconds.
   *
   * It is important to realize that such a RateLimiter has a very superficial memory
   * of the past: it only remembers the last request. What if the RateLimiter was unused for
   * a long period of time, then a request arrived and was immediately granted?
   * This RateLimiter would immediately forget about that past underutilization. This may
   * result in either underutilization or overflow, depending on the real world consequences
   * of not using the expected rate.
   *
   * Past underutilization could mean that excess resources are available. Then, the RateLimiter
   * should speed up for a while, to take advantage of these resources. This is important
   * when the rate is applied to networking (limiting bandwidth), where past underutilization
   * typically translates to "almost empty buffers", which can be filled immediately.
   *
   * On the other hand, past underutilization could mean that "the server responsible for
   * handling the request has become less ready for future requests", i.e. its caches become
   * stale, and requests become more likely to trigger expensive operations (a more extreme
   * case of this example is when a server has just booted, and it is mostly busy with getting
   * itself up to speed).
   *
   * To deal with such scenarios, we add an extra dimension, that of "past underutilization",
   * modeled by "storedPermits" variable. This variable is zero when there is no
   * underutilization, and it can grow up to maxStoredPermits, for sufficiently large
   * underutilization. So, the requested permits, by an invocation acquire(permits),
   * are served from:
   * - stored permits (if available)
   * - fresh permits (for any remaining permits)
   *
   * How this works is best explained with an example:
   *
   * For a RateLimiter that produces 1 token per second, every second
   * that goes by with the RateLimiter being unused, we increase storedPermits by 1.
   * Say we leave the RateLimiter unused for 10 seconds (i.e., we expected a request at time
   * X, but we are at time X + 10 seconds before a request actually arrives; this is
   * also related to the point made in the last paragraph), thus storedPermits
   * becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of acquire(3)
   * arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how this is
   * translated to throttling time is discussed later). Immediately after, assume that an
   * acquire(10) request arriving. We serve the request partly from storedPermits,
   * using all the remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits
   * produced by the rate limiter.
   *
   * We already know how much time it takes to serve 3 fresh permits: if the rate is
   * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7
   * stored permits? As explained above, there is no unique answer. If we are primarily
   * interested to deal with underutilization, then we want stored permits to be given out
   * /faster/ than fresh ones, because underutilization = free resources for the taking.
   * If we are primarily interested to deal with overflow, then stored permits could
   * be given out /slower/ than fresh ones. Thus, we require a (different in each case)
   * function that translates storedPermits to throtting time.
   *
   * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake).
   * The underlying model is a continuous function mapping storedPermits
   * (from 0.0 to maxStoredPermits) onto the 1/rate (i.e. intervals) that is effective at the given
   * storedPermits. "storedPermits" essentially measure unused time; we spend unused time
   * buying/storing permits. Rate is "permits / time", thus "1 / rate = time / permits".
   * Thus, "1/rate" (time / permits) times "permits" gives time, i.e., integrals on this
   * function (which is what storedPermitsToWaitTime() computes) correspond to minimum intervals
   * between subsequent requests, for the specified number of requested permits.
   *
   * Here is an example of storedPermitsToWaitTime:
   * If storedPermits == 10.0, and we want 3 permits, we take them from storedPermits,
   * reducing them to 7.0, and compute the throttling for these as a call to
   * storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
   * evaluate the integral of the function from 7.0 to 10.0.
   *
   * Using integrals guarantees that the effect of a single acquire(3) is equivalent
   * to { acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc,
   * since the integral of the function in [7.0, 10.0] is equivalent to the sum of the
   * integrals of [7.0, 8.0], [8.0, 9.0], [9.0, 10.0] (and so on), no matter
   * what the function is. This guarantees that we handle correctly requests of varying weight
   * (permits), /no matter/ what the actual function is - so we can tweak the latter freely.
   * (The only requirement, obviously, is that we can compute its integrals).
   *
   * Note well that if, for this function, we chose a horizontal line, at height of exactly
   * (1/QPS), then the effect of the function is non-existent: we serve storedPermits at
   * exactly the same cost as fresh ones (1/QPS is the cost for each). We use this trick later.
   *
   * If we pick a function that goes /below/ that horizontal line, it means that we reduce
   * the area of the function, thus time. Thus, the RateLimiter becomes /faster/ after a
   * period of underutilization. If, on the other hand, we pick a function that
   * goes /above/ that horizontal line, then it means that the area (time) is increased,
   * thus storedPermits are more costly than fresh permits, thus the RateLimiter becomes
   * /slower/ after a period of underutilization.
   *
   * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
   * completely unused, and an expensive acquire(100) request comes. It would be nonsensical
   * to just wait for 100 seconds, and /then/ start the actual task. Why wait without doing
   * anything? A much better approach is to /allow/ the request right away (as if it was an
   * acquire(1) request instead), and postpone /subsequent/ requests as needed. In this version,
   * we allow starting the task immediately, and postpone by 100 seconds future requests,
   * thus we allow for work to get done in the meantime instead of waiting idly.
   *
   * This has important consequences: it means that the RateLimiter doesn't remember the time
   * of the _last_ request, but it remembers the (expected) time of the _next_ request. This
   * also enables us to tell immediately (see tryAcquire(timeout)) whether a particular
   * timeout is enough to get us to the point of the next scheduling time, since we always
   * maintain that. And what we mean by "an unused RateLimiter" is also defined by that
   * notion: when we observe that the "expected arrival time of the next request" is actually
   * in the past, then the difference (now - past) is the amount of time that the RateLimiter
   * was formally unused, and it is that amount of time which we translate to storedPermits.
   * (We increase storedPermits with the amount of permits that would have been produced
   * in that idle time). So, if rate == 1 permit per second, and arrivals come exactly
   * one second after the previous, then storedPermits is _never_ increased -- we would only
   * increase it for arrivals _later_ than the expected one second.
   */

  /**
   * This implements the following function where coldInterval = coldFactor * stableInterval.
   *
   *          ^ throttling
   *          |
   *    cold  +                  /
   * interval |                 /.
   *          |                / .
   *          |               /  .   <-- "warmup period" is the area of the trapezoid 梯形 between
   *          |              /   .       thresholdPermits and maxPermits
   *          |             /    .
   *          |            /     .
   *          |           /      .
   *   stable +----------/  WARM .
   * interval |          .   UP  .
   *          |          . PERIOD.
   *          |          .       .
   *        0 +----------+-------+--------------> storedPermits  存储的令牌数
   *          0 thresholdPermits maxPermits
   *                  令牌阈值      最大令牌数
   *
   *
   * Before going into the details of this particular function, let's keep in mind the basics:
   * 1) The state of the RateLimiter (storedPermits) is a vertical line in this figure. 令牌数是横轴
   * 当限流器不使用时，令牌数增大，直到maxPermits
   * 2) When the RateLimiter is not used, this goes right (up to maxPermits)
   * 当限流器使用时，令牌数变小，直到 0
   * 3) When the RateLimiter is used, this goes left (down to zero), since if we have storedPermits,
   *    we serve from those first
   *
   *    增加令牌数的比率为maxPermits / warmupPeriod，保证从0到最大的时间为warmupPeriod
   * 4) When _unused_, we go right at a constant rate! The rate at which we move to
   *    the right is chosen as maxPermits / warmupPeriod.  This ensures that the time it takes to
   *    go from 0 to maxPermits is equal to warmupPeriod.
   *
   *    当被使用时，需要计算花费k saved permits的时间
   * 5) When _used_, the time it takes, as explained in the introductory class note, is
   *    equal to the integral of our function, between X permits and X-K permits, assuming
   *    we want to spend K saved permits.
   *
   *    向左移动的时间等于k相关的面积
   *    In summary, the time it takes to move to the left (spend K permits), is equal to the
   *    area of the function of width == K.
   *
   *    从maxpermits到thresholdPermits的时间也等于warmupPeriod
   *    从thresholdpermits到0需要warmupPeriod/2
   *    Assuming we have saturated demand, the time to go from maxPermits to thresholdPermits is
   *    equal to warmupPeriod.  And the time to go from thresholdPermits to 0 is
   *    warmupPeriod/2.  (The reason that this is warmupPeriod/2 is to maintain the behavior of
   *    the original implementation where coldFactor was hard coded as 3.)
   *
   *  It remains to calculate thresholdsPermits and maxPermits.
   *
   *  - The time to go from thresholdPermits to 0 is equal to the integral of the function between
   *    0 and thresholdPermits.  This is thresholdPermits * stableIntervals.  By (5) it is also
   *    equal to warmupPeriod/2.  Therefore
   *
   *        thresholdPermits = 0.5 * warmupPeriod / stableInterval.
   *
   *  - The time to go from maxPermits to thresholdPermits is equal to the integral of the function
   *    between thresholdPermits and maxPermits.  This is the area of the pictured trapezoid, and it
   *    is equal to 0.5 * (stableInterval + coldInterval) * (maxPermits - thresholdPermits).  It is
   *    also equal to warmupPeriod, so
   *
   *        maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval).
   */
  /**
   * 平滑预热限流 漏桶算法
   */
  static final class SmoothWarmingUp extends SmoothRateLimiter {
    private final long warmupPeriodMicros;
    /**
     * The slope of the line from the stable interval (when permits == 0), to the cold interval
     * (when permits == maxPermits)
     */
    private double slope;
    private double thresholdPermits;
    private double coldFactor;

    SmoothWarmingUp(
        SleepingStopwatch stopwatch, long warmupPeriod, TimeUnit timeUnit, double coldFactor) {
      super(stopwatch);
      this.warmupPeriodMicros = timeUnit.toMicros(warmupPeriod);
      this.coldFactor = coldFactor;
    }

    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = maxPermits;
      /**
       * coldInterval = stable * 比率
       */
      double coldIntervalMicros = stableIntervalMicros * coldFactor;
      /**
       * 阈值 permits = 预热时间 / 稳定产生一个令牌的时间 * 0.5
       */
      thresholdPermits = 0.5 * warmupPeriodMicros / stableIntervalMicros;

      /**
       * 最大permits = 阈值 + warmupPeriodMicros / ((stableIntervalMicros + coldIntervalMicros ) / 2)
       * 使用稳定产生一个令牌的时间间隔和最大产生一个令牌的时间间隔的平均值
       */
      maxPermits = thresholdPermits
          + 2.0 * warmupPeriodMicros / (stableIntervalMicros + coldIntervalMicros);
      /**
       * 计算斜率
       */
      slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        // if we don't special-case this, we would get storedPermits == NaN, below
        storedPermits = 0.0;
      } else {
        storedPermits = (oldMaxPermits == 0.0)
            ? maxPermits // initial state is cold
            : storedPermits * maxPermits / oldMaxPermits;
      }
    }

    @Override
    long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
      /**
       * 当前permits超出阈值的部分
       */
      double availablePermitsAboveThreshold = storedPermits - thresholdPermits;
      long micros = 0;
      // measuring the integral on the right part of the function (the climbing line)
      /**
       * 如果当前存储的令牌数超出thresholdPermits
       */
      if (availablePermitsAboveThreshold > 0.0) {
        /**
         * 在阈值右侧并且需要被消耗的令牌数量
         */
        double permitsAboveThresholdToTake = min(availablePermitsAboveThreshold, permitsToTake);

        /**
         * 梯形的面积
         *
         * 高 * (顶 * 底) / 2
         *
         * 高是 permitsAboveThresholdToTake 也就是右侧需要消费的令牌数
         * 底 较长 permitsToTime(availablePermitsAboveThreshold)
         * 顶 较短 permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake)
         */
        micros = (long) (permitsAboveThresholdToTake
            * (permitsToTime(availablePermitsAboveThreshold)
            + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake)) / 2.0);
        /**
         * 减去已经获取的在阈值右侧的令牌数
         */
        permitsToTake -= permitsAboveThresholdToTake;
      }
      // measuring the integral on the left part of the function (the horizontal line)
      /**
       * 平稳时期的面积，正好是长乘宽
       */
      micros += (stableIntervalMicros * permitsToTake);
      return micros;
    }

    /**
     * 横纵坐标转换
     * @param permits
     * @return
     */
    private double permitsToTime(double permits) {
      /**
       * 固定值 + 横坐标 * 斜率
       */
      return stableIntervalMicros + permits * slope;
    }

    @Override
    double coolDownIntervalMicros() {
      /**
       * 每秒增加的令牌数为 warmup时间/maxPermits. 这样的话，在warmuptime时间内，就就增张的令牌数量
       * 为 maxPermits
       */
      return warmupPeriodMicros / maxPermits;
    }
  }

  /**
   * This implements a "bursty" RateLimiter, where storedPermits are translated to
   * zero throttling. The maximum number of permits that can be saved (when the RateLimiter is
   * unused) is defined in terms of time, in this sense: if a RateLimiter is 2qps, and this
   * time is specified as 10 seconds, we can save up to 2 * 10 = 20 permits.
   *
   * 可以处理突发限流，
   *
   * 存放固定容量令牌（token）的桶，按照固定速率往桶里添加令牌
   *
   *
   * 令牌按照固定的速率放入，stableIntervalMicros 每xx秒1个
   * 桶中最多存放b个令牌，当桶满时，新添加的令牌被丢弃或拒绝 maxPermits
   * 需要n个令牌，如果有则直接放行，否则要消耗时间，但是这个是让第二个进来的人等待。
   */
  /**
   * 令牌桶算法 平滑突发限流
   */
  static final class SmoothBursty extends SmoothRateLimiter {
    /** The work (permits) of how many seconds can be saved up if this RateLimiter is unused? */
    final double maxBurstSeconds;

    SmoothBursty(SleepingStopwatch stopwatch, double maxBurstSeconds) {
      super(stopwatch);
      this.maxBurstSeconds = maxBurstSeconds;
    }

    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = this.maxPermits;
      maxPermits = maxBurstSeconds * permitsPerSecond;
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        // if we don't special-case this, we would get storedPermits == NaN, below
        storedPermits = maxPermits;
      } else {
        storedPermits = (oldMaxPermits == 0.0)
            ? 0.0 // initial state
            : storedPermits * maxPermits / oldMaxPermits;
      }
    }

    @Override
    long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
      return 0L;
    }

    @Override
    double coolDownIntervalMicros() {
      return stableIntervalMicros;
    }
  }

  /**
   * The currently stored permits.
   */
  double storedPermits;

  /**
   * The maximum number of stored permits.
   */
  double maxPermits;

  /**
   * The interval between two unit requests, at our stable rate. E.g., a stable rate of 5 permits
   * per second has a stable interval of 200ms.
   * 每组请求之前的间隔，比如说如果限流为5 qps,则每个请求需要间隔200ms
   */
  double stableIntervalMicros;

  /**
   * The time when the next request (no matter its size) will be granted. After granting a
   * request, this is pushed further in the future. Large requests push this further than small
   * requests.
   */
  private long nextFreeTicketMicros = 0L; // could be either in the past or future

  private SmoothRateLimiter(SleepingStopwatch stopwatch) {
    super(stopwatch);
  }

  @Override
  final void doSetRate(double permitsPerSecond, long nowMicros) {
    resync(nowMicros);
    double stableIntervalMicros = SECONDS.toMicros(1L) / permitsPerSecond;
    this.stableIntervalMicros = stableIntervalMicros;
    doSetRate(permitsPerSecond, stableIntervalMicros);
  }

  abstract void doSetRate(double permitsPerSecond, double stableIntervalMicros);

  @Override
  final double doGetRate() {
    return SECONDS.toMicros(1L) / stableIntervalMicros;
  }

  @Override
  final long queryEarliestAvailable(long nowMicros) {
    return nextFreeTicketMicros;
  }


  /**
   * 在同步块中操作，每次只能有一个线程进行操作 同步快中时睡眠，而不是wait即不会释放锁
   *
   * 如果某一段时间内，申请大量的令牌，令牌数超过了本地剩余的令牌数，系统会超前支付一定的令牌数，并且调整下次
   * 产生令牌的时间，当再有线程申请时，如果没有到时间将会进入睡眠以等待令牌产生，并且计算此处超前支付的数量
   * 更新下一次nextFreeTickMicros。每次线程都睡眠等待，会一直超前支付，具体可自行假设多个线程请求令牌即可，
   * 因为线程睡眠前会调用reserveEarliestAvailable,
   * 又会重新计算nextFreeTicketMicros,当有线程来时会与新的nextFreeTicketMicros比较
   *
   *
   * 比如说，当前一个令牌也没有，resync一下，还没有到nextFreeTicketMicros(时间1),但是可以预先释放令牌，所以计算一下acquire
   * 令牌所需的时间，添加到还没有到nextFreeTicketMicros(时间2)，目前线程只需要等待到旧的nextFreeTicketMicros(时间1)即可
   *
   * 然后第二个线程又来获取一个令牌，resync一下，此时的 nextFreeTicketMicros为时间2,发现还没有到时间2，但是也可以
   * 预先释放一下，nextFreeTicketMicros变成了时间3，第二个线程只需要等待到时间2即可。
   *
   *
   * 就是后一个线程必须等待到前一个线程获取完令牌的理论时间。
   *
   * 本次请求需要为上次请求的预消费行为埋单，这也是RateLimiter可以预消费(处理突发)的原理所在
   * @param requiredPermits
   * @param nowMicros
   * @return
   */
  @Override
  final long reserveEarliestAvailable(int requiredPermits, long nowMicros) {
    // //重新刷新令牌数
    resync(nowMicros);
    long returnValue = nextFreeTicketMicros;
    /**
     * 获取当前已有的permits和需要的permits中比较小的。
     */
    double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
    /**
     * freshPermits是需要预先支付的令牌，要么为0，要么就是requirePermits - storedPermits
     */
    double freshPermits = requiredPermits - storedPermitsToSpend;
    // 为什么要加waitMicros，因为会突然涌入大量请求，而现有令牌数又不够用，因此会预先支付一定的令牌数
    // waitMicros即是产生预先支付令牌的数量时间，则将下次要添加令牌的时间应该计算时间+watiMicros
    /**
     * storedPermitsToWaitTime + freshPermits * stableIntervalMicros
     */
    long waitMicros = storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend)
        + (long) (freshPermits * stableIntervalMicros);

    try {
      this.nextFreeTicketMicros = LongMath.checkedAdd(nextFreeTicketMicros, waitMicros);
    } catch (ArithmeticException e) {
      this.nextFreeTicketMicros = Long.MAX_VALUE;
    }
    // //更新令牌数，最低数量为0
    this.storedPermits -= storedPermitsToSpend;
    return returnValue;
  }

  /**
   * 将要花费的令牌数占令牌总数的比率转变为限流时间。
   * Translates a specified portion of our currently stored permits which we want to
   * spend/acquire, into a throttling time. Conceptually, this evaluates the integral
   * of the underlying function we use, for the range of
   * [(storedPermits - permitsToTake), storedPermits].
   *
   * <p>This always holds: {@code 0 <= permitsToTake <= storedPermits}
   */
  abstract long storedPermitsToWaitTime(double storedPermits, double permitsToTake);

  /**
   * Returns the number of microseconds during cool down that we have to wait to get a new permit.
   *
   * 返回cool down阶段我们获取一个令牌所需等待的时间
   */
  abstract double coolDownIntervalMicros();

  /**
   * Updates {@code storedPermits} and {@code nextFreeTicketMicros} based on the current time.
   */
  void resync(long nowMicros) {
    // if nextFreeTicket is in the past, resync to now
    if (nowMicros > nextFreeTicketMicros) {
      storedPermits = min(maxPermits,
          storedPermits
            + (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros());
      nextFreeTicketMicros = nowMicros;
    }
  }
}
