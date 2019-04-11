package psync.runtime

import psync._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._
import io.netty.buffer.{ByteBuf,PooledByteBufAllocator}
import io.netty.channel.socket._
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import psync.utils.serialization.{KryoSerializer, KryoByteBufInput, KryoByteBufOutput}
import scala.collection.mutable.PriorityQueue
import scala.math.{Ordering,max}
import Message.MessageOrdering
import psync.utils.{CircularBuffer, LongBitSet}

//TODO what should be the interface ?
//- val lock = new java.util.concurrent.locks.ReentrantLock
//- @volatile var roundStart: Long
//- var roundDuration: Long //TO is roundStart+roundDuration
//- def newPacket(dp: DatagramPacket): Unit
//- def interrupt(inst: Short): Unit or stop(inst: Short)
//TODO break it into smaller parts
//-for learning TO/roundDuration
//  - used a discounted sum / geometric serie: coeff, window, expected RTT
//  - step increment/decrement
//  - fixed
trait InstHandler {

  /** Handle packets received from this instance */
  def newPacket(msg: Message): Unit

  /** This instance should stop.
   *  Since there might be multiple threads working. It might take
   *  some time after this call returns until the instance actually
   *  finishes. */
  def interrupt(inst: Int): Unit

}

class InstanceHandler[IO,P <: Process[IO]](proc: P,
                          alg: Algorithm[IO,P],
                          rt: psync.runtime.Runtime) extends Runnable with InstHandler {

  protected val buffer = new ArrayBlockingQueue[Message](rt.options.bufferSize)

  protected val sendWhenCatchingUp = rt.options.sendWhenCatchingUp
  protected val delayFirstSend = rt.options.delayFirstSend

  protected var instance: Short = 0
  protected var self: ProcessID = new ProcessID(-1)
  
  private final val block = Long.MinValue
  protected var timeout = rt.options.timeout
  protected var roundStart: Long = 0
  protected var strict = false
  
  protected var currentRound = 0
  protected var nextRound = 0

  /** keep track of the processes which have already send for the round */
  protected var from = LongBitSet.empty
  
  /** catch-up after nbrByzantine+1 messages have been received */
  protected val nbrByzantine = rt.options.nbrByzantine
  /** keep the max round seen for each process (used for deciding when to catch-up) */
  protected var maxRnd: Array[Int] = Array(0)
  /** Since we might block on the round, we buffer messages that will be delivered later. */
  protected var pendingMessages: Array[PriorityQueue[Message]] = Array(new PriorityQueue[Message]()(Message.MinMessageOrdering)) //TODO check that this is the right ordering
  /** discard when there are two many messages from one process */
  protected var maxPending = 32 //TODO as option

  protected val globalSizeHint = rt.options.packetSize
  protected val kryoIn = new KryoByteBufInput(null)
  protected val kryoOut = new KryoByteBufOutput(null)
  protected val kryo = {
    val k = KryoSerializer.serializer
    proc.registerSerializer(k)
    k
  }
  protected val allocator = PooledByteBufAllocator.DEFAULT

  /** A new packet is received and should be processed */
  def newPacket(msg: Message) = {
    if (!buffer.offer(msg)) {
      Logger("InstanceHandler", Warning, "Replica " + self.id + " too many packets for instance " + instance)
      msg.release
    }
  }

  /** Forward the packet to the defaultHandler in another thread/task */
  protected def default(msg: Message) {
    rt.default(msg)
  }

  /** Prepare the handler for a execution.
   *  call this just before giving it to the executor */
  def prepare(io: IO, g: Group, inst: Short, msgs: Set[Message]) {
    // clear the buffer
    freeRemainingMessages

    // init the process
    proc.setGroup(g)
    proc.init(io)

    // init this
    instance = inst
    self = g.self
    currentRound = 0
    nextRound = 0

    // checkResources
    val s = g.size
    assert(s < 64)
    from = LongBitSet.empty
    if (pendingMessages.size != s) {
      pendingMessages = Array.fill(s)(new PriorityQueue[Message]()(Message.MinMessageOrdering))
    }
    if (maxRnd.size != s) {
      maxRnd = Array.fill[Int](s)(0)
    }

    // enqueue pending messages
    msgs.foreach(newPacket)
  }

  protected def freeRemainingMessages {
    var pkt = buffer.poll
    while(pkt != null) {
      pkt.release
      pkt = buffer.poll
    }
    from = LongBitSet.empty
    var i = 0
    while (i < pendingMessages.size) {
      maxRnd(i) = 0
      val q = pendingMessages(i)
      q.foreach( _.release )
      q.clear
      i += 1
    }
  }

  @volatile protected var again = true

  def interrupt(inst: Int) {
    if (instance == inst)
      again = false
  }

  @inline private final def more = {
    if (again && !Thread.interrupted) {
      true
    } else {
      again = false
      false
    }
  }
  @inline private final def timeDiff(t1: Int, t2: Int) = t1 - t2
  @inline private final def rndDiff(rnd: Int) = timeDiff(rnd, currentRound)
  @inline private final def needCatchingUp = rndDiff(nextRound) > 0
  @inline private final def readyToProgress = needCatchingUp && !strict

  def run {
    Logger("InstanceHandler", Info, "starting instance " + instance)
    again = true
    var msg: Message = null
    try {
      if (delayFirstSend > 0) {
        Thread.sleep(delayFirstSend)
      }
      // one round
      while(more) {
        initRound
        // send the messages at the beginning of the round
        if (msg == null || sendWhenCatchingUp) {
          send
        }
        // deliver pending messages
        deliverPending
        // check msg as well (store if needed)
        if (msg != null) {
          val msgRound = msg.round
          if (rndDiff(msgRound) == 0) {
            processPacket(msg)
            msg = null
          } else if (!readyToProgress) {
            //getting blocked, buffer the message
            assert(rndDiff(msgRound) >= 0)
            //TODO maxPending
            pendingMessages(msg.sender.id).enqueue(msg)
            msg = null
          }
        }
        // accumulate messages
        var timedout = false
        while (msg == null &&        // in the process of catching up
               !readyToProgress &&   // has not yet received enough messages
               more)                // not interrupted
        {
          // try receive a new message
          if (timeout == block) {
            msg = buffer.take()
          } else {
            val to = roundStart + timeout - java.lang.System.currentTimeMillis()
            if (to >= 0) {
              msg = buffer.poll(timeout, TimeUnit.MILLISECONDS)
            }
          }
          // check that we have a message that we can handle
          if (msg != null) {
            if (checkInstanceAndTag(msg)) {
              // process pending message
              var msgRound = msg.round
              // update the highest seen round from msg.sender
              val sid = msg.sender.id
              if (msgRound - maxRnd(sid) > 0) {
                maxRnd(sid) = msgRound
              }
              val late = rndDiff(msgRound)
              if (late < 0) {
                // late message, ignore
                msg.release
                msg = null
              } else if (late == 0) {
                // message for the current round
                processPacket(msg)
                msg = null
               } else { // late > 0
                // check if we need to catch-up or store msg in pendingMessages
                computeNextRound
                if (!readyToProgress) {
                  //TODO maxPending
                  pendingMessages(msg.sender.id).enqueue(msg)
                  msg = null
                }
              }
            } else {
              msg = null
            }
          } else {
            //Logger("InstanceHandler", Warning, instance + " timeout")
            timedout = true
            nextRound = currentRound + 1
            strict = false
          }
        }
        again &= update(timedout || msg != null) //consider catching up (msg != null) as TO
      }
    } catch {
      case _: java.lang.InterruptedException => ()
      case t: Throwable =>
        Logger("InstanceHandler", Error, "got an error " + t + " terminating instance: " + instance + "\n  " + t.getStackTrace.mkString("\n  "))
    } finally {
      if (msg != null) {
        msg.release
      }
      stop
    }
  }

  ///////////////////
  // current round //
  ///////////////////

  @inline private final def deliverPending {
    var i = 0
    while (i < pendingMessages.size) {
      val q = pendingMessages(i)
      while (!q.isEmpty && rndDiff(q.head.round) <= 0) {
        val msg = q.dequeue
        assert(rndDiff(msg.round) == 0)
        processPacket(msg)
      }
      i += 1
    }
  }

  @inline private final def computeNextRound {
    if (nbrByzantine == 0) {
      // find max relative to currentRound
      var i = 0
      var currMax = currentRound
      while (i < maxRnd.size) {
        if (maxRnd(i) - currMax > 0) {
          currMax = maxRnd(i)
        }
        i += 1
      }
      nextRound = currMax
    } else {
      // the right way of computing the next round is to sort maxRnd and drop the nbrByzantine highest values
      // see https://en.wikipedia.org/wiki/Selection_algorithm but since we have small size we can just sort ...
      //TODO more efficient
      nextRound = currentRound + math.max(0, maxRnd.map(_ - currentRound).sorted.apply(maxRnd.size - nbrByzantine - 1))
    }
  }

  protected def checkProgress(p: Progress, init: Boolean) {
    //TODO check monotonicity of progress
    if (p.isTimeout) {
      timeout = p.timeout
      strict = p.isStrict
    } else if (p.isGoAhead) {
      strict = false
      nextRound = currentRound + max(nextRound - currentRound, 1)
    } else if (p.isWaitMessage) {
      timeout = block
      strict = p.isStrict
    } else if (p.isUnchanged) {
      if (init) {
        Logger.logAndThrow("InstanceHandler", Error, "Progress of init should not be Unchanged.")
      } else {
        // nothing to do I guess
      }
    } else {
      Logger.logAndThrow("InstanceHandler", Error, "Progress !?!?")
    }
  }

  protected def initRound {
    from = LongBitSet.empty
    roundStart = java.lang.System.currentTimeMillis()
    checkProgress(proc.init, true)
  }

  // responsible for freeing the msg if returns false
  protected def checkInstanceAndTag(msg: Message): Boolean = {
    if (instance != msg.instance) { // wrong instance
      msg.release
      false
    } else if (msg.flag == Flags.normal) {
      // nothing to do we are fine
      true
    } else if (msg.flag == Flags.dummy) {
      Logger("InstanceHandler", Debug, self.id + ", " + instance + "dummy flag (ignoring)")
      msg.release
      false
    } else {
      if (msg.flag == Flags.error) {
        Logger("InstanceHandler", Warning, "error flag (pushing to user)")
      }
      default(msg)
      false
    }
  }

  protected def processPacket(msg: Message) {
    val sender = msg.sender
    if (!from.get(sender.id)) {
      from = from.set(sender.id)
      assert(msg.round == currentRound, msg.round + " vs " + currentRound)
      val buffer = msg.bufferAfterTag
      kryoIn.setBuffer(buffer)
      checkProgress(proc.receive(kryo, sender, kryoIn), false)
      kryoIn.setBuffer(null: ByteBuf)
    }
    msg.release
  }

  protected def update(didTimeout: Boolean) = {
    Logger("InstanceHandler", Debug, "Replica " + self.id + ", instance " + instance + " delivering for round " + currentRound + (if (didTimeout) " with TO" else ""))
    val shouldTerminate = proc.update(didTimeout)
    //assert(needCatchingUp || !more, "currentRound " + currentRound + ", nextRound " + nextRound) 
    currentRound += 1
    maxRnd(self.id) = currentRound
    shouldTerminate
  }

  protected def send {
    val tag = Tag(instance, currentRound)
    var sent = 0
    var buffer: ByteBuf = null
    def alloc(sizeHint: Int): KryoByteBufOutput = {
      buffer = if (sizeHint > 0) allocator.buffer(sizeHint + tag.size)
               else if (globalSizeHint > 0) allocator.buffer(globalSizeHint + tag.size)
               else allocator.buffer()
      buffer.writeLong(tag.underlying)
      kryoOut.setBuffer(buffer)
      kryoOut
    }
    def sending(pid: ProcessID) {
      assert(pid != self)
      rt.send(pid, buffer)
      sent += 1
    }
    checkProgress(proc.send(kryo, alloc, sending), false)
    kryoOut.setBuffer(null: ByteBuf)
    Logger("InstanceHandler", Debug, "Replica " + self.id + ", instance " + instance + " sending for round " + currentRound + " -> " + sent + "\n")
  }

  protected def stop {
    Logger("InstanceHandler", Info, "stopping instance " + instance)
    rt.remove(instance)
    freeRemainingMessages
    alg.recycle(this)
    Thread.interrupted //clear interrupt
  }

}
