package round.predicate

import io.netty.channel.Channel
import round.runtime.InstanceDispatcher
import java.util.concurrent.ArrayBlockingQueue

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._


class PredicatePool(channel: Channel,
                    dispatcher: InstanceDispatcher,
                    options: Map[String, String] = Map.empty) {

  final val defaultSize = 32

  protected val maxSize = {
    try {
      options.getOrElse("predicatePool", defaultSize.toString).toInt
    } catch { case e: Exception =>
      Logger("PredicatePool", Warning, "maxSize: wrong format, using " + defaultSize)
      defaultSize
    }
  }

  protected val queue = new ArrayBlockingQueue[Predicate](maxSize)

  def get = {
    var pred = queue.poll
    if (pred == null) {
      pred = new ToPredicate(channel, dispatcher, options)
      pred.setPool(this)
    } 
    pred
  }

  def recycle(p: Predicate) = {
    p.reset
    queue.offer(p)
  }

}