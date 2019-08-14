/*
 * Copyright (C) 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.{lang, util}

import com.lightbend.kafkalagexporter.Domain.{GroupOffsets, PartitionOffsets}
import com.lightbend.kafkalagexporter.KafkaClient.KafkaClientContract
import org.apache.kafka.clients.admin._
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition => KafkaTopicPartition}

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object KafkaClient {
  val AdminClientConfigRetries = 0 // fail faster when there are transient connection errors, use supervision strategy for backoff
  val CommonClientConfigRetryBackoffMs = 1000 // longer interval between retry attempts so we don't overload clusters (default = 100ms)
  val ConsumerConfigAutoCommit = false

  def apply(cluster: KafkaCluster, groupId: String, clientTimeout: FiniteDuration)(implicit ec: ExecutionContext): KafkaClientContract =
    new KafkaClient(cluster, groupId, clientTimeout)(ec)

  trait KafkaClientContract {
    def getGroups(): Future[(List[String], List[Domain.GroupTopicPartition])]
    def getGroupOffsets(now: Long, groups: List[String], groupTopicPartitions: List[Domain.GroupTopicPartition]): Future[GroupOffsets]
    def getLatestOffsets(now: Long, topicPartitions: Set[Domain.TopicPartition]): Try[PartitionOffsets]
    def close(): Unit
  }

  private def kafkaFuture[T](kafkaFuture: KafkaFuture[T])(implicit ec: ExecutionContext, clientTimeout: Duration): Future[T] = {
    val p = Promise[T]()
    Future {
      try {
        p.success(kafkaFuture.get(clientTimeout.toMillis, TimeUnit.MILLISECONDS))
      } catch {
        case ex: Throwable => p.failure(ex)
      }
    }(ec)
    p.future
  }

  private def createAdminClient(cluster: KafkaCluster, clientTimeout: FiniteDuration): AdminClient = {
    val props = new Properties()
    // AdminClient config: https://kafka.apache.org/documentation/#adminclientconfigs
    props.putAll(cluster.adminClientProperties.asJava)
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapBrokers)
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, clientTimeout.toMillis.toString)
    props.put(AdminClientConfig.RETRIES_CONFIG, AdminClientConfigRetries.toString)
    props.put(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG, CommonClientConfigRetryBackoffMs.toString)
    AdminClient.create(props)
  }

  private def createConsumerClient(cluster: KafkaCluster, groupId: String, clientTimeout: FiniteDuration): KafkaConsumer[Byte, Byte] = {
    val props = new Properties()
    val deserializer = (new ByteArrayDeserializer).getClass.getName
    // https://kafka.apache.org/documentation/#consumerconfigs
    props.putAll(cluster.consumerProperties.asJava)
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapBrokers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfigAutoCommit.toString)
    //props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
    props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, clientTimeout.toMillis.toString)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
    props.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, CommonClientConfigRetryBackoffMs.toString)
    new KafkaConsumer(props)
  }

  // extension methods to convert between Offsets.TopicPartition and org.apache.kafka.common.TopicPartition
  private[kafkalagexporter] implicit class OffsetsTopicPartitionOps(ktp: KafkaTopicPartition) {
    def asDomain: Domain.TopicPartition = Domain.TopicPartition(ktp.topic(), ktp.partition())
  }

  private[kafkalagexporter] implicit class KafkaTopicPartitionOps(tp: Domain.TopicPartition) {
    def asKafka: KafkaTopicPartition = new KafkaTopicPartition(tp.topic, tp.partition)
  }
}

class KafkaClient private[kafkalagexporter](cluster: KafkaCluster,
                                            groupId: String,
                                            clientTimeout: FiniteDuration)
                                           (implicit ec: ExecutionContext) extends KafkaClientContract {
  import KafkaClient._

  private implicit val _clientTimeout: Duration = clientTimeout.toJava

  private lazy val adminClient: AdminClient = createAdminClient(cluster, clientTimeout)
  private lazy val consumer: KafkaConsumer[Byte,Byte] = createConsumerClient(cluster, groupId, clientTimeout)

  private lazy val listGroupOptions = new ListConsumerGroupsOptions().timeoutMs(_clientTimeout.toMillis().toInt)
  private lazy val describeGroupOptions = new DescribeConsumerGroupsOptions().timeoutMs(_clientTimeout.toMillis().toInt)
  private lazy val listConsumerGroupsOptions = new ListConsumerGroupOffsetsOptions().timeoutMs(_clientTimeout.toMillis().toInt)

  /**
    * Get a list of consumer groups
    */
  def getGroups(): Future[(List[String], List[Domain.GroupTopicPartition])] = {
    for {
      groups <- kafkaFuture(adminClient.listConsumerGroups(listGroupOptions).all())
      groupIds = groups.asScala.map(_.groupId()).toList
      groupDescriptions <- kafkaFuture(adminClient.describeConsumerGroups(groupIds.asJava, describeGroupOptions).all())
    } yield {
      val gtps = groupDescriptions.asScala.flatMap { case (id, desc) => groupTopicPartitions(id, desc) }.toList
      (groupIds, gtps)
    }
  }

  private[kafkalagexporter] def groupTopicPartitions(groupId: String, desc: ConsumerGroupDescription): List[Domain.GroupTopicPartition] = {
    val groupTopicPartitions = for {
      member <- desc.members().asScala
      ktp <- member.assignment().topicPartitions().asScala
    } yield Domain.GroupTopicPartition(
      groupId,
      member.clientId(),
      member.consumerId(),
      member.host(),
      ktp.topic(),
      ktp.partition()
    )
    groupTopicPartitions.toList
  }

  /**
    * Get latest offsets for a set of topic partitions.
    */
  def getLatestOffsets(now: Long, topicPartitions: Set[Domain.TopicPartition]): Try[PartitionOffsets] = Try {
    val offsets: util.Map[KafkaTopicPartition, lang.Long] = consumer.endOffsets(topicPartitions.map(_.asKafka).asJava, _clientTimeout)
    topicPartitions.map(tp => tp -> LookupTable.Point(offsets.get(tp.asKafka).toLong,now)).toMap
  }

  /**
    * Get last committed Consumer Group offsets for all group topic partitions given a list of consumer groups.  When a
    * topic partition has no matched Consumer Group offset then a default offset of 0 is provided.
    * @return A series of Future's for Consumer Group offsets requests to Kafka.
    */
  def getGroupOffsets(now: Long, groups: List[String], gtps: List[Domain.GroupTopicPartition]): Future[GroupOffsets] = {
    val groupOffsetsF: Future[List[GroupOffsets]] = Future.sequence {
      groups.map { group =>
        kafkaFuture(getListConsumerGroupOffsets(group))
          .map(offsetMap => getGroupOffsets(now, gtps, offsetMap.asScala.toMap))
      }
    }

    groupOffsetsF
      .map(_.flatten.toMap)
      .map(go => getOffsetOrZero(gtps, go))
  }


  /**
    * Call to `AdminClient` to get group offset info.  This is only its own method so it can be mocked out in a test
    * because it's not possible to instantiate or mock the `ListConsumerGroupOffsetsResult` type for some reason.
    */
  private[kafkalagexporter] def getListConsumerGroupOffsets(group: String): KafkaFuture[util.Map[KafkaTopicPartition, OffsetAndMetadata]] = {
    adminClient
      .listConsumerGroupOffsets(group, listConsumerGroupsOptions)
      .partitionsToOffsetAndMetadata()
  }

  /**
    * Backfill any group topic partitions with no offset as 0
    */
  private[kafkalagexporter] def getOffsetOrZero(
                      gtps: List[Domain.GroupTopicPartition],
                      groupOffsets: GroupOffsets): GroupOffsets =
    gtps.map(gtp => gtp -> groupOffsets.getOrElse(gtp, None)).toMap

  /**
    * Transform Kafka response into `GroupOffsets`
    */
  private[kafkalagexporter] def getGroupOffsets(now: Long,
                                                gtps: List[Domain.GroupTopicPartition],
                                                offsetMap: Map[KafkaTopicPartition, OffsetAndMetadata]): GroupOffsets =
    (for {
      gtp <- gtps
      offset <- offsetMap.get(gtp.tp.asKafka).map(_.offset())
    } yield gtp -> Some(LookupTable.Point(offset, now))).toMap

  def close(): Unit = {
    adminClient.close(_clientTimeout)
    consumer.close(_clientTimeout)
  }
}
