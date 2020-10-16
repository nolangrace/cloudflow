/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.flink

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.DataStreamSink
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka._

import com.typesafe.config._
import cloudflow.streamlets._
import java.{ util ⇒ ju }

/**
 * An implementation of `FlinkStreamletContext`
 */
class FlinkStreamletContextImpl(
    private[cloudflow] override val streamletDefinition: StreamletDefinition,
    @transient env: StreamExecutionEnvironment,
    override val config: Config
) extends FlinkStreamletContext(streamletDefinition, env) {

  /**
   * Returns a `DataStream[In]` from the `inlet` to be added as the data source
   * of the computation graph
   *
   * @param inlet the inlet port to read from
   * @return the data read as `DataStream[In]`
   */
  override def readStream[In: TypeInformation](inlet: CodecInlet[In]): DataStream[In] = {
    val topic            = findTopicForPort(inlet)
    val srcTopic         = topic.name
    val groupId          = topic.groupId(streamletDefinition.appId, streamletRef, inlet)
    val bootstrapServers = runtimeBootstrapServers(topic)
    val propsMap = Map("bootstrap.servers" -> bootstrapServers, "group.id" -> groupId, "auto.offset.reset" -> "earliest") ++
          topic.kafkaConsumerProperties

    val properties = new ju.Properties()
    propsMap.foreach { case (k, v) => properties.put(k, v) }

    val consumer = new FlinkKafkaConsumer[Array[Byte]](
      srcTopic,
      new FlinkKafkaCodecDeserializationSchema(),
      properties
    )

    // whether consumer should commit offsets back to Kafka on checkpoints
    // this is true by default: still making it explicit here. As such, Flink manages offsets
    // on its own - it just commits to Kafka for your information only
    // also this setting is honored only when checkpointing is on - otherwise the property in Kafka
    // "enable.auto.commit" is considered
    consumer.setCommitOffsetsOnCheckpoints(true)
    env.addSource(consumer).map(inlet.codec.decode(_))
  }

  /**
   * Adds a sink to the `stream`
   *
   * @param outlet the outlet used to write the result of execution
   * @param stream stream used to write the result of execution
   *
   * @return the `DataStream` used to write to sink
   */
  override def writeStream[Out: TypeInformation](outlet: CodecOutlet[Out], stream: DataStream[Out]): DataStreamSink[Out] = {

    val topic            = findTopicForPort(outlet)
    val destTopic        = topic.name
    val bootstrapServers = runtimeBootstrapServers(topic)

    val propsMap = Map("bootstrap.servers" -> bootstrapServers, "batch.size" -> "0") ++
          topic.kafkaProducerProperties

    val properties = new ju.Properties()
    propsMap.foreach { case (k, v) => properties.put(k, v) }

    stream.addSink(
      new FlinkKafkaProducer[Out](
        destTopic,
        new FlinkKafkaCodecSerializationSchema[Out](outlet, destTopic),
        properties,
        FlinkKafkaProducer.Semantic.AT_LEAST_ONCE
      )
    )
  }
}
