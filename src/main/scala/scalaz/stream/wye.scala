package scalaz.stream

import collection.immutable.Queue
import concurrent.duration._

import scalaz.{\/, -\/, \/-}
import Process._
import These.{This,That}

trait wye {

  /** 
   * Transform the left input of the given `Wye` using a `Process1`. 
   */
  def attachL[I0,I,I2,O](p: Process1[I0,I])(w: Wye[I,I2,O]): Wye[I0,I2,O] = w match {
    case h@Halt(_) => h
    case Emit(h,t) => Emit(h, attachL(p)(t))
    case AwaitL(recv, fb, c) => 
      p match {
        case Emit(h, t) => attachL(t)(feedL(h)(w))
        case Await1(recvp, fbp, cp) => 
          await(L[I0]: Env[I0,I2]#Y[I0])(
            recvp andThen (attachL(_)(w)),
            attachL(fbp)(w), 
            attachL(cp)(w))
        case h@Halt(_) => attachL(h)(fb)
      }
    case AwaitR(recv, fb, c) => 
      awaitR[I2].flatMap(recv andThen (attachL(p)(_))).
      orElse(attachL(p)(fb), attachL(p)(c))
    case AwaitBoth(recv, fb, c) => 
      p match {
        case Emit(h, t) => attachL(t)(feedL(h)(w))
        case Await1(recvp, fbp, cp) => 
          await(Both[I0,I2]: Env[I0,I2]#Y[These[I0,I2]])(
            { case This(i0) => attachL(p.feed1(i0))(w)
              case That(i2) => attachL(p)(feed1R(i2)(w))
              case These(i0,i2) => attachL(p.feed1(i0))(feed1R(i2)(w))
            }, 
            attachL(fbp)(w), 
            attachL(cp)(w))
        case h@Halt(_) => attachL(h)(fb)
      }
  }

  /** 
   * Transform the right input of the given `Wye` using a `Process1`. 
   */
  def attachR[I,I1,I2,O](p: Process1[I1,I2])(w: Wye[I,I2,O]): Wye[I,I1,O] =
    flip(attachL(p)(flip(w)))

  /** 
   * A `Wye` which emits values from its right branch, but allows up to `n`
   * elements from the left branch to enqueue unanswered before blocking
   * on the right branch.
   */
  def boundedQueue[I](n: Int): Wye[Any,I,I] = 
    yipWithL(n)((i,i2) => i2) 

  /** 
   * After each input, dynamically determine whether to read from the left, right, or both,
   * for the subsequent input, using the provided functions `f` and `g`. The returned 
   * `Wye` begins by reading from the left side and is left-biased--if a read of both branches
   * returns a `These(x,y)`, it uses the signal generated by `f` for its next step.
   */
  def dynamic[I,I2](f: I => wye.Request, g: I2 => wye.Request): Wye[I,I2,These[I,I2]] = {
    import wye.Request._
    def go(signal: wye.Request): Wye[I,I2,These[I,I2]] = signal match {
      case L => awaitL[I].flatMap { i => emit(This(i)) fby go(f(i)) }
      case R => awaitR[I2].flatMap { i2 => emit(That(i2)) fby go(g(i2)) }
      case Both => awaitBoth[I,I2].flatMap {
        case t@This(i) => emit(t) fby go(f(i)) 
        case t@That(i2) => emit(t) fby go(g(i2)) 
        case t@These(i,_) => emit(t) fby go(f(i)) // left-biased
      }
    }
    go(L)
  }

  /** 
   * Invokes `dynamic` with `I == I2`, and produces a single `I` output. Output is
   * left-biased: if a `These(i1,i2)` is emitted, this is translated to an 
   * `emitSeq(List(i1,i2))`.
   */
  def dynamic1[I](f: I => wye.Request): Wye[I,I,I] =
    dynamic(f, f).flatMap { 
      case This(i) => emit(i)
      case That(i) => emit(i)
      case These(i1,i2) => emitSeq(List(i2,i2))
    }

  /** 
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def either[I,I2]: Wye[I,I2,I \/ I2] = 
    merge[I \/ I2].contramapL((i: I) => -\/(i)).
                   contramapR((i2: I2) => \/-(i2))

  /** 
   * Feed a single `These` value to a `Wye`.
   */
  def feed1[I,I2,O](i: These[I,I2])(w: Wye[I,I2,O]): Wye[I,I2,O] = 
    i match {
      case This(i) => feed1L(i)(w)
      case That(i2) => feed1R(i2)(w)
      case These(i,i2) => feed1Both(i,i2)(w)
    }

  /** Feed a sequence of values to the left branch of a `Wye`. */
  def feedL[I,I2,O](i: Seq[I])(w: Wye[I,I2,O]): Wye[I,I2,O] = {
    var buf = i
    var cur = w
    def ok(w: Wye[I,I2,O]): Boolean = w match {
      case AwaitL(_,_,_) => true
      case AwaitBoth(_,_,_) => true
      case _ => false
    }
    while (!buf.isEmpty && ok(cur)) {
      val h = buf.head
      cur = feed1L(h)(cur) 
      buf = buf.tail
    }
    if (buf.isEmpty) cur
    else cur match {
      case h@Halt(_) => h
      case AwaitR(recv,fb,c) => 
        await(R[I2]: Env[I,I2]#Y[I2])(recv andThen (feedL(buf)), fb, c) 
      case Emit(o, t) => 
        Emit(o, feedL(buf)(t))
      case _ => sys.error("impossible! main `feedL` loop resulted in: " + cur)
    }
  }

  /** Feed a sequence of values to the right branch of a `Wye`. */
  def feedR[I,I2,O](i2: Seq[I2])(w: Wye[I,I2,O]): Wye[I,I2,O] = 
    flip(feedL(i2)(flip(w)))

  /** Feed a single value to the left branch of a `Wye`. */
  def feed1L[I,I2,O](i: I)(w: Wye[I,I2,O]): Wye[I,I2,O] = 
    w match {
      case Halt(_) => w
      case Emit(h, t) => Emit(h, feed1L(i)(t))
      case AwaitL(recv,fb,c) => 
        try recv(i)
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
      case AwaitBoth(recv,fb,c) => 
        try recv(This(i))
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
      case AwaitR(recv,fb,c) =>
        await(R[I2]: Env[I,I2]#Y[I2])(recv andThen (feed1L(i)), feed1L(i)(fb), feed1L(i)(c)) 
    }

  /** Feed a single value to the right branch of a `Wye`. */
  def feed1R[I,I2,O](i2: I2)(w: Wye[I,I2,O]): Wye[I,I2,O] = 
    w match {
      case Halt(_) => w
      case Emit(h, t) => Emit(h, feed1R(i2)(t))
      case AwaitR(recv,fb,c) => 
        try recv(i2)
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
      case AwaitBoth(recv,fb,c) => 
        try recv(That(i2))
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
      case AwaitL(recv,fb,c) =>
        await(L[I]: Env[I,I2]#Y[I])(recv andThen (feed1R(i2)), feed1R(i2)(fb), feed1R(i2)(c)) 
    }

  /** Feed a value to both the right and left branch of a `Wye`. */
  def feed1Both[I,I2,O](i: I, i2: I2)(w: Wye[I,I2,O]): Wye[I,I2,O] = 
    w match {
      case Halt(_) => w
      case Emit(h, t) => Emit(h, feed1Both(i, i2)(t))
      case AwaitL(recv,fb,c) =>
        try feed1R(i2)(recv(i))
        catch {
          case End => feed1R(i2)(fb)
          case e: Throwable => feed1R(i2)(c.causedBy(e))
        }
      case AwaitR(recv,fb,c) => 
        try feed1L(i)(recv(i2))
        catch {
          case End => feed1L(i)(fb)
          case e: Throwable => feed1L(i)(c.causedBy(e))
        }
      case AwaitBoth(recv,fb,c) => 
        try recv(These(i,i2))
        catch {
          case End => fb
          case e: Throwable => c.causedBy(e)
        }
    }

  /** 
   * Convert right requests to left requests and vice versa.
   */
  def flip[I,I2,O](w: Wye[I,I2,O]): Wye[I2,I,O] = w match {
    case h@Halt(_) => h 
    case Emit(h, t) => Emit(h, flip(t))
    case AwaitL(recv, fb, c) => 
      await(R[I]: Env[I2,I]#Y[I])(recv andThen (flip), flip(fb), flip(c))
    case AwaitR(recv, fb, c) => 
      await(L[I2]: Env[I2,I]#Y[I2])(recv andThen (flip), flip(fb), flip(c))
    case AwaitBoth(recv, fb, c) => 
      await(Both[I2,I])((t: These[I2,I]) => flip(recv(t.flip)), flip(fb), flip(c))
  }

  /** 
   * Nondeterminstic interleave of both inputs. Emits values whenever either
   * of the inputs is available.
   */
  def merge[I]: Wye[I,I,I] = {
    def go(biasL: Boolean): Wye[I,I,I] = 
      receiveBoth[I,I,I]({
        case This(i) => emit(i) fby (go(!biasL))
        case That(i) => emit(i) fby (go(!biasL))
        case These(i,i2) => 
          if (biasL) emitSeq(List(i,i2)) fby (go(!biasL)) 
          else       emitSeq(List(i2,i)) fby (go(!biasL)) 
      } 
    )
    go(true)
  }

  /** 
   * A `Wye` which echoes the left branch while draining the right,
   * taking care to make sure that the right branch is never more 
   * than `maxUnacknowledged` behind the left. For example: 
   * `src.connect(snk)(observe(10))` will output the the same thing 
   * as `src`, but will as a side effect direct output to `snk`, 
   * blocking on `snk` if more than 10 elements have enqueued 
   * without a response.
   */
  def drainR[I](maxUnacknowledged: Int): Wye[I,Any,I] = 
    yipWithL[I,Any,I](maxUnacknowledged)((i,i2) => i)

  /** 
   * A `Wye` which echoes the right branch while draining the left,
   * taking care to make sure that the left branch is never more 
   * than `maxUnacknowledged` behind the right. For example: 
   * `src.connect(snk)(observe(10))` will output the the same thing 
   * as `src`, but will as a side effect direct output to `snk`, 
   * blocking on `snk` if more than 10 elements have enqueued 
   * without a response.
   */ 
  def drainL[I](maxUnacknowledged: Int): Wye[Any,I,I] =
    flip(drainR(maxUnacknowledged))

  /**
   * Let through the right branch as long as the left branch is `false`,
   * listening asynchronously for the left branch to become `true`. 
   */
  def interrupt[I]: Wye[Boolean, I, I] = awaitBoth[Boolean,I].flatMap {
    case That(i) => emit(i) ++ interrupt
    case This(kill) => if (kill) halt else interrupt
    case These(kill, i) => if (kill) halt else emit(i) ++ interrupt
  }

  /** 
   * A `Wye` which blocks on the right side when either a) the age of the oldest unanswered 
   * element from the left size exceeds the given duration, or b) the number of unanswered 
   * elements from the left exceeds `maxSize`.
   */
  def timedQueue[I](d: Duration, maxSize: Int = Int.MaxValue): Wye[Duration,I,I] = {
    def go(q: Vector[Duration]): Wye[Duration,I,I] = 
      awaitBoth[Duration,I].flatMap {
        case This(d2) => 
          if (q.size >= maxSize || (d2 - q.headOption.getOrElse(d2) > d))
            awaitR[I].flatMap(i => emit(i) fby go(q.drop(1)))  
          else
            go(q :+ d2)
        case That(i) => emit(i) fby (go(q.drop(1)))
        case These(t,i) => emit(i) fby (go(q.drop(1) :+ t))
      }
    go(Vector())
  }

  /** 
   * `Wye` which repeatedly awaits both branches, emitting any values
   * received from the right. Useful in conjunction with `connect`,
   * for instance `src.connect(snk)(unboundedQueue)`
   */
  def unboundedQueue[I]: Wye[Any,I,I] = 
    awaitBoth[Any,I].flatMap {
      case This(any) => halt
      case That(i) => emit(i) fby unboundedQueue
      case These(_,i) => emit(i) fby unboundedQueue
    }

  /** Nondeterministic version of `zip` which requests both sides in parallel. */
  def yip[I,I2]: Wye[I,I2,(I,I2)] = yipWith((_,_))

  /** 
   * Left-biased, buffered version of `yip`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def yipL[I,I2](n: Int): Wye[I,I2,(I,I2)] = 
    yipWithL(n)((_,_))

  /** Nondeterministic version of `zipWith` which requests both sides in parallel. */
  def yipWith[I,I2,O](f: (I,I2) => O): Wye[I,I2,O] = 
    awaitBoth[I,I2].flatMap {
      case This(i) => awaitR[I2].flatMap(i2 => emit(f(i,i2)))  
      case That(i2) => awaitL[I].flatMap(i => emit(f(i,i2)))  
      case These(i,i2) => emit(f(i,i2))
    }.repeat

  /** 
   * Left-biased, buffered version of `yipWith`. Allows up to `n` elements to enqueue on the
   * left unanswered before requiring a response from the right. If buffer is empty,
   * always reads from the left. 
   */ 
  def yipWithL[I,O,O2](n: Int)(f: (I,O) => O2): Wye[I,O,O2] = {
    def go(buf: Vector[I]): Wye[I,O,O2] =
      if (buf.size > n) awaitR[O].flatMap { o => 
        emit(f(buf.head,o)) ++ go(buf.tail)
      }
      else if (buf.isEmpty) awaitL[I].flatMap { i => go(buf :+ i) }
      else awaitBoth[I,O].flatMap {
        case This(i) => go(buf :+ i)
        case That(o) => emit(f(buf.head,o)) ++ go(buf.tail)
        case These(i,o) => emit(f(buf.head,o)) ++ go(buf :+ i) 
      }
    go(Vector())
  }
}

object wye extends wye {
  /** Simple enumeration for dynamically generated `Wye` request types. See `wye.dynamic`. */
  trait Request
  object Request {
    case object L extends Request
    case object R extends Request
    case object Both extends Request
  }
}
