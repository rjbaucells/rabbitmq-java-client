// Copyright (c) 2017-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.test;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class ChannelAsyncCompletableFutureTest extends BrokerTestCase {

    ExecutorService executor, methodArgumentExecutor;

    @Parameterized.Parameters
    public static Collection<ExecutorService> params() {
        List<ExecutorService> executors = new ArrayList<>();
        executors.add(null);
        executors.add(Executors.newSingleThreadExecutor());
        return executors;
    }

    public ChannelAsyncCompletableFutureTest(ExecutorService methodArgumentExecutor) {
        this.methodArgumentExecutor = methodArgumentExecutor;
    }

    String queue;
    String exchange;

    @Before public void init() {
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        queue = UUID.randomUUID().toString();
        exchange = UUID.randomUUID().toString();
    }

    @After public void tearDown() throws IOException {
        executor.shutdownNow();
        channel.queueDelete(queue);
        channel.exchangeDelete(exchange);
        if (methodArgumentExecutor != null) {
            methodArgumentExecutor.shutdownNow();
        }
    }

    @Test
    public void async() throws Exception {
        channel.confirmSelect();

        CountDownLatch latch = new CountDownLatch(1);
        AMQP.Queue.Declare queueDeclare = new AMQImpl.Queue.Declare.Builder()
            .queue(queue)
            .durable(true)
            .exclusive(false)
            .autoDelete(false)
            .arguments(null)
            .build();

        channel.asyncCompletableRpc(queueDeclare, null)
            .thenComposeAsync(action -> {
                try {
                    return channel.asyncCompletableRpc(new AMQImpl.Exchange.Declare.Builder()
                        .exchange(exchange)
                        .type("fanout")
                        .durable(false)
                        .autoDelete(false)
                        .arguments(null)
                        .build(), methodArgumentExecutor);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, executor).thenComposeAsync(action -> {
                try {
                    return channel.asyncCompletableRpc(new AMQImpl.Queue.Bind.Builder()
                        .queue(queue)
                        .exchange(exchange)
                        .routingKey("")
                        .arguments(null)
                        .build(), methodArgumentExecutor);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, executor).thenAcceptAsync(action -> {
                try {
                    channel.basicPublish("", queue, null, "dummy".getBytes());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
        }, executor).thenAcceptAsync((whatever) -> {
                try {
                    channel.basicConsume(queue, true, new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                            latch.countDown();
                        }
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
        }, executor);
        channel.waitForConfirmsOrDie(1000);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

}