/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.client.latency;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * broker容错机制的思路及实现步骤：
 *
 * 1、在消息发送失败，mq根据消息发送耗时来预测该broker不可用的时长，并将broker名称，及”预计恢复时长“，存储于ConcurrentHashMap<String, FaultItem> faultItemTable中
 * 2、在开启消息容错后，选择消息队列时，会根据当前时间与FaultItem中该broker的预计恢复时间做比较，
 *      若(System.currentTimeMillis() - startTimestamp) >= 0，则预计该broker恢复正常，选择该broker的消息队列
 * 3、若所有的broker都预计不可用，随机选择一个不可用的broker，从路由信息中选择下一个消息队列，重置其brokerName，queueId，进行消息发送
 *
 */
public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();

    /**
     * 延迟故障容错，维护每个Broker的发送消息的延迟
     */
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();

    private boolean sendLatencyFaultEnable = false;

    // 发送延迟时间数组
    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L};
    // 故障不可用时长数组
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};

    public long[] getNotAvailableDuration() {
        return notAvailableDuration;
    }

    public void setNotAvailableDuration(final long[] notAvailableDuration) {
        this.notAvailableDuration = notAvailableDuration;
    }

    public long[] getLatencyMax() {
        return latencyMax;
    }

    public void setLatencyMax(final long[] latencyMax) {
        this.latencyMax = latencyMax;
    }

    public boolean isSendLatencyFaultEnable() {
        return sendLatencyFaultEnable;
    }

    public void setSendLatencyFaultEnable(final boolean sendLatencyFaultEnable) {
        this.sendLatencyFaultEnable = sendLatencyFaultEnable;
    }

    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        if (this.sendLatencyFaultEnable) {
            try {
                // 获取 brokerName=lastBrokerName && 可用的一个消息队列（根据latencyFaultTolerance.isAvailable判断）
                int index = tpInfo.getSendWhichQueue().getAndIncrement();
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) {
                        if (null == lastBrokerName || mq.getBrokerName().equals(lastBrokerName))
                            return mq;
                    }
                }

                // 选择一个相对好的broker，并获得其对应的一个消息队列，不考虑该队列的可用性
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);
                if (writeQueueNums > 0) {
                    final MessageQueue mq = tpInfo.selectOneMessageQueue();
                    // 因为路由信息中随机获取到的下一个MessageQueue不一定存在于选择到的notBestBroker中，
                    // 所以需要重置MessageQueue的brokerName和queueId
                    if (notBestBroker != null) {
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq;
                } else {
                    latencyFaultTolerance.remove(notBestBroker);
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }

            // 随机选择一个消息队列，不考虑队列的可用性
            return tpInfo.selectOneMessageQueue();
        }

        //获得 lastBrokerName 对应的一个消息队列，不考虑该队列的可用性
        return tpInfo.selectOneMessageQueue(lastBrokerName);
    }

    /**
     * 更新broker异常容错信息
     * @param brokerName
     * @param currentLatency
     * @param isolation 是否隔离。当开启隔离时，默认延迟为30000。目前主要用于发送消息异常时
     */
    public void updateFaultItem(final String brokerName, final long currentLatency, boolean isolation) {
        if (this.sendLatencyFaultEnable) {
            long duration = computeNotAvailableDuration(isolation ? 30000 : currentLatency);
            this.latencyFaultTolerance.updateFaultItem(brokerName, currentLatency, duration);
        }
    }

    /**
     * 根据消息发送时长(currentLatency)，计算broker不可用时长(duration)，即如果消息发送时间越久，mq会认为broker不可用的时长越久，
     * broker不可用时长是个经验值，如果传入isolation为true，表示默认当前发送时长为30000L，即broker不可用时长为600000L
     * @param currentLatency
     * @return
     */
    private long computeNotAvailableDuration(final long currentLatency) {
        for (int i = latencyMax.length - 1; i >= 0; i--) {
            if (currentLatency >= latencyMax[i])
                return this.notAvailableDuration[i];
        }

        return 0;
    }
}
