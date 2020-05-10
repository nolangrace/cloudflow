package connectedcar.streamlets

import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.util.Timeout
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorAttributes, OverflowStrategy, Supervision}

import cloudflow.akkastream.scaladsl.RunnableGraphStreamletLogic
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.{AvroInlet, AvroOutlet}
import cloudflow.akkastream.{AkkaStreamlet, Clustering}

import connectedcar.actors.{ConnectedCarActor, ConnectedCarERecordWrapper}
import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}

import scala.concurrent.duration._

object ConnectedCarCluster extends AkkaStreamlet with Clustering {
  val in    = AvroInlet[ConnectedCarERecord]("in")
  val out   = AvroOutlet[ConnectedCarAgg]("out", m ⇒ m.driver.toString)
  val shape = StreamletShape(in).withOutlets(out)

  override def createLogic = new RunnableGraphStreamletLogic() {

    val (queue:SourceQueueWithComplete[ConnectedCarAgg], source)= Source
      .queue[ConnectedCarAgg](1000, OverflowStrategy.backpressure)
      .preMaterialize()

    implicit val timeout: Timeout = 3.seconds
    implicit val typedScheduler = system.toTyped.scheduler

    /* runnable graph that commits offset after actor acks message */
    def runnableGraph = {
      sourceWithCommittableContext(in)
        .mapAsync(5) {
          msg ⇒ (carRegion.ask[ConnectedCarAgg](ref =>
            ShardingEnvelope(msg.carId.toString(), ConnectedCarERecordWrapper(msg, ref))))
        }
        .log("error logging")
        .to(committableSink)
        .withAttributes(ActorAttributes.supervisionStrategy(resumingDecider))
        .run()

      source
        .log("error logging")
        .to(plainSink(out))
    }

    val TypeKey = EntityTypeKey[ConnectedCarERecordWrapper]("Counter")

    val carRegion: ActorRef[ShardingEnvelope[ConnectedCarERecordWrapper]] =
      ClusterSharding(system.toTyped).init(Entity(TypeKey)(createBehavior = entityContext =>
        ConnectedCarActor(entityContext.entityId)))

    val resumingDecider: Supervision.Decider = {
      case _ => Supervision.Resume
    }
  }
}
