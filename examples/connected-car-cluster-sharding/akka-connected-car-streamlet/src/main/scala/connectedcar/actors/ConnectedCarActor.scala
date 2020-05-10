package connectedcar.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import connectedcar.data.{ConnectedCarAgg, ConnectedCarERecord}

case class ConnectedCarERecordWrapper(record: ConnectedCarERecord, sender:ActorRef[ConnectedCarAgg])

object ConnectedCarActor {
  def apply(entityId: String): Behavior[ConnectedCarERecordWrapper] = {
    def updated(numberOfRecords: Int, driverName: String, averageSpeed: Double, currentSpeed: Double): Behavior[ConnectedCarERecordWrapper] = {
      Behaviors.receive { (context, message) => {
          context.log.info("Updated CarId: " + entityId + " Driver Name: " + driverName + " CarSpeed: " +
          currentSpeed + " From Actor:" + message.sender.path)

          val newAverage = ((averageSpeed * numberOfRecords) + message.record.speed) / (numberOfRecords + 1)
          val newNumberOfRecords = numberOfRecords+1

          message.sender ! ConnectedCarAgg(message.record.carId, message.record.driver, averageSpeed, newNumberOfRecords)

          updated(newNumberOfRecords, message.record.driver, newAverage, message.record.speed)
        }
      }
    }

    updated(0, "", 0, 0.0)
  }
}