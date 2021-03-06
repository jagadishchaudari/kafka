/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server

import java.util.concurrent.TimeUnit

import kafka.network.RequestChannel
import kafka.utils.KafkaScheduler
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics._
import org.apache.kafka.common.utils.Time
import org.apache.kafka.server.quota.ClientQuotaCallback

import scala.collection.JavaConverters._


class ClientRequestQuotaManager(private val config: ClientQuotaManagerConfig,
                                private val metrics: Metrics,
                                private val time: Time,
                                private val schedulerOpt: Option[KafkaScheduler],
                                threadNamePrefix: String,
                                quotaCallback: Option[ClientQuotaCallback])
                                extends ClientQuotaManager(config, metrics, QuotaType.Request, time, schedulerOpt, threadNamePrefix, quotaCallback) {
  val maxThrottleTimeMs = TimeUnit.SECONDS.toMillis(this.config.quotaWindowSizeSeconds)
  def exemptSensor = getOrCreateSensor(exemptSensorName, exemptMetricName)

  def recordExempt(value: Double) {
    exemptSensor.record(value)
  }

  /**
    * Records that a user/clientId changed request processing time being throttled. If quota has been violated, return
    * throttle time in milliseconds. Throttle time calculation may be overridden by sub-classes.
    * @param request client request
    * @return Number of milliseconds to throttle in case of quota violation. Zero otherwise
    */
  def maybeRecordAndGetThrottleTimeMs(request: RequestChannel.Request): Int = {
    if (request.apiRemoteCompleteTimeNanos == -1) {
      // When this callback is triggered, the remote API call has completed
      request.apiRemoteCompleteTimeNanos = time.nanoseconds
    }

    if (quotasEnabled) {
      request.recordNetworkThreadTimeCallback = Some(timeNanos => recordNoThrottle(
        getOrCreateQuotaSensors(request.session, request.header.clientId), nanosToPercentage(timeNanos)))
      recordAndGetThrottleTimeMs(request.session, request.header.clientId,
        nanosToPercentage(request.requestThreadTimeNanos), time.milliseconds())
    } else {
      0
    }
  }

  def maybeRecordExempt(request: RequestChannel.Request): Unit = {
    if (quotasEnabled) {
      request.recordNetworkThreadTimeCallback = Some(timeNanos => recordExempt(nanosToPercentage(timeNanos)))
      recordExempt(nanosToPercentage(request.requestThreadTimeNanos))
    }
  }

  override protected def throttleTime(clientMetric: KafkaMetric): Long = {
    math.min(super.throttleTime(clientMetric), maxThrottleTimeMs)
  }

  override protected def clientRateMetricName(quotaMetricTags: Map[String, String]): MetricName = {
    metrics.metricName("request-time", QuotaType.Request.toString,
      "Tracking request-time per user/client-id",
      quotaMetricTags.asJava)
  }

  private def exemptMetricName: MetricName = {
    metrics.metricName("exempt-request-time", QuotaType.Request.toString,
                   "Tracking exempt-request-time utilization percentage")
  }

  private def exemptSensorName: String = "exempt-" + QuotaType.Request

  private def nanosToPercentage(nanos: Long): Double = nanos * ClientQuotaManagerConfig.NanosToPercentagePerSecond

}
