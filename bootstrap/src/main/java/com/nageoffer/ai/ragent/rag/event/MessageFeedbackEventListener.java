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

package com.nageoffer.ai.ragent.rag.event;

import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 消息反馈事件监听器
 * 替代原 RocketMQ MessageFeedbackConsumer，将点赞/点踩事件异步持久化到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFeedbackEventListener {

    private final MessageFeedbackService feedbackService;

    @Async
    @EventListener
    public void onEvent(MessageFeedbackEvent event) {
        log.info("[事件监听] 开始处理点赞/点踩事件，messageId={}, userId={}, vote={}",
                event.getMessageId(), event.getUserId(), event.getVote());
        feedbackService.submitFeedbackByEvent(event);
    }
}
