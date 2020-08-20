package sample.shoppingcart

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.{ DiscoverySupport, SendProducer }
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.Offset
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.AtLeastOnceProjection
import akka.projection.scaladsl.SourceProvider
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

object PublishEventsProjection {

  private def createProjectionFor(
      system: ActorSystem[_],
      topic: String,
      sendProducer: SendProducer[String, Array[Byte]],
      index: Int): AtLeastOnceProjection[Offset, EventEnvelope[ShoppingCart.Event]] = {
    val tag = s"${ShoppingCart.TagPrefix}-$index"
    val sourceProvider: SourceProvider[Offset, EventEnvelope[ShoppingCart.Event]] =
      EventSourcedProvider.eventsByTag[ShoppingCart.Event](
        system = system,
        readJournalPluginId = CassandraReadJournal.Identifier,
        tag = tag)

    CassandraProjection.atLeastOnce(
      projectionId = ProjectionId("cart-events", tag),
      sourceProvider,
      handler = () => new PublishEventsProjectionHandler(system, topic, sendProducer))
  }

  def init(system: ActorSystem[_], projectionParallelism: Int): Unit = {
    val topic = system.settings.config.getString("shopping-cart.kafka-topic")
    val config = system.settings.config.getConfig("shopping-cart.kafka.producer")
    import akka.actor.typed.scaladsl.adapter._ // FIXME might not be needed in later Alpakka Kafka version?
    val producerSettings =
      ProducerSettings(config, new StringSerializer, new ByteArraySerializer)
        .withEnrichAsync(DiscoverySupport.producerBootstrapServers(config)(system.toClassic))
    val sendProducer = SendProducer(producerSettings)(system.toClassic)

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close sendProducer") {
      () =>
        sendProducer.close()
    }

    ShardedDaemonProcess(system).init(
      name = "PublishEventsProjection",
      projectionParallelism,
      index => ProjectionBehavior(createProjectionFor(system, topic, sendProducer, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop))
  }

}
