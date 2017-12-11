/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.gossip;

import static java.util.stream.Collectors.toList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.zeebe.clustering.gossip.GossipEventType;
import io.zeebe.clustering.gossip.MembershipEventType;
import io.zeebe.dispatcher.*;
import io.zeebe.gossip.membership.Member;
import io.zeebe.gossip.protocol.*;
import io.zeebe.transport.*;
import io.zeebe.transport.impl.RequestResponseHeaderDescriptor;
import io.zeebe.transport.impl.TransportHeaderDescriptor;
import io.zeebe.util.actor.ActorReference;
import io.zeebe.util.actor.ActorScheduler;
import org.agrona.DirectBuffer;
import org.junit.rules.ExternalResource;
import org.slf4j.MDC;

public class GossipRule extends ExternalResource
{

    private final Supplier<ActorScheduler> actionSchedulerSupplier;
    private final GossipConfiguration configuration;
    private final SocketAddress socketAddress;
    private final String memberId;

    private Gossip gossip;

    private ClientTransport clientTransport;
    private Dispatcher clientSendBuffer;

    private BufferingServerTransport serverTransport;
    private Dispatcher serverSendBuffer;
    private Dispatcher serverReceiveBuffer;

    private ActorReference gossipActorRef;
    private ActorReference testSubscriptionActorRef;

    private LocalMembershipListener localMembershipListener;
    private ReceivedEventsCollector receivedEventsCollector = new ReceivedEventsCollector();

    public GossipRule(final Supplier<ActorScheduler> actionSchedulerSupplier, final GossipConfiguration configuration, final String host, final int port)
    {
        this.actionSchedulerSupplier = actionSchedulerSupplier;
        this.configuration = configuration;
        this.socketAddress = new SocketAddress(host, port);
        this.memberId = String.format("%s:%d", host, port);
    }

    @Override
    protected void before() throws Throwable
    {
        receivedEventsCollector.clear();

        final String name = socketAddress.toString();

        final ActorScheduler actorScheduler = actionSchedulerSupplier.get();

        serverSendBuffer = Dispatchers
                .create("serverSendBuffer-" + name)
                .bufferSize(32 * 1024 * 1024)
                .subscriptions("sender")
                .actorScheduler(actorScheduler)
                .build();

        serverReceiveBuffer = Dispatchers
                .create("serverReceiveBuffer-" + name)
                .bufferSize(32 * 1024 * 1024)
                .subscriptions("sender")
                .actorScheduler(actorScheduler)
                .build();

        serverTransport = Transports
                .newServerTransport()
                .sendBuffer(serverSendBuffer)
                .bindAddress(socketAddress.toInetSocketAddress())
                .scheduler(actorScheduler)
                .buildBuffering(serverReceiveBuffer);

        clientSendBuffer = Dispatchers
                .create("clientSendBuffer-" + name)
                .bufferSize(32 * 1024 * 1024)
                .subscriptions("sender")
                .actorScheduler(actorScheduler)
                .build();

        clientTransport = Transports
                .newClientTransport()
                .sendBuffer(clientSendBuffer)
                .requestPoolSize(128)
                .scheduler(actorScheduler)
                .inputListener(receivedEventsCollector)
                .build();

        // TODO make it more safe
        clientTransport = spy(clientTransport);

        final ClientOutput clientOutput = spy(clientTransport.getOutput());

        when(clientTransport.getOutput()).thenReturn(clientOutput);

        gossip = new Gossip(socketAddress, serverTransport, clientTransport, configuration);

        gossipActorRef = actorScheduler.schedule(() ->
        {
            // make it easier to distinguish different gossip runners
            MDC.put("gossip-id", name);

            return gossip.doWork();
        });

        localMembershipListener = new LocalMembershipListener();
        gossip.addMembershipListener(localMembershipListener);

        serverReceiveBuffer.openSubscriptionAsync("received-events-collector").thenAccept(sub ->
        {
            testSubscriptionActorRef = actorScheduler.schedule(() -> sub.poll(receivedEventsCollector, 32));
        });
    }

    @Override
    protected void after()
    {
        gossipActorRef.close();
        testSubscriptionActorRef.close();

        serverTransport.closeAsync()
            .thenCompose(v -> serverSendBuffer.closeAsync())
            .thenCompose(v -> serverReceiveBuffer.closeAsync());

        clientTransport.closeAsync()
            .thenCompose(v -> clientSendBuffer.closeAsync());
    }

    public void join(GossipRule... contactPoints)
    {
        final List<SocketAddress> contactPointList = Arrays.asList(contactPoints).stream().map(c -> c.socketAddress).collect(toList());

        getController().join(contactPointList);
    }

    public void interruptConnectionTo(GossipRule other)
    {
        final ClientRequest clientRequest = mock(ClientRequest.class);

        final ClientOutput clientOutput = clientTransport.getOutput();
        doReturn(clientRequest).when(clientOutput).sendRequest(argThat(r -> r.getAddress().equals(other.socketAddress)), any());
    }

    public void reconnectTo(GossipRule other)
    {
        final ClientOutput clientOutput = clientTransport.getOutput();
        doCallRealMethod().when(clientOutput).sendRequest(argThat(r -> r.getAddress().equals(other.socketAddress)), any());
    }

    public GossipController getController()
    {
        return gossip;
    }

    public boolean receivedEvent(GossipEventType eventType, GossipRule sender)
    {
        return receivedEventsCollector.gossipEvents()
                .filter(e -> e.getEventType() == eventType)
                .filter(e -> e.getSender().equals(sender.memberId))
                .findFirst()
                .isPresent();
    }

    public boolean receivedMembershipEvent(MembershipEventType eventType, GossipRule member)
    {
        return receivedEventsCollector.membershipEvents()
                .filter(e -> e.getType() == eventType)
                .filter(e -> e.getMemberId().equals(member.memberId))
                .findFirst()
                .isPresent();
    }

    public void clearReceivedEvents()
    {
        receivedEventsCollector.clear();
    }

    public boolean hasMember(GossipRule member)
    {
        return localMembershipListener.hasMember(member.memberId);
    }

    public Member getMember(GossipRule member)
    {
        return localMembershipListener.getMember(member.memberId);
    }

    private static class ReceivedEventsCollector implements ClientInputListener, FragmentHandler
    {
        private class ReceivedEvent
        {
            private final List<MembershipEvent> membershipEvents = new ArrayList<>();

            private final GossipEvent gossipEvent = new GossipEvent(null, event ->
            {
                final MembershipEventImpl membershipEvent = new MembershipEventImpl();
                membershipEvent.wrap(event.getMemberId(), event.getType(), event.getGossipTerm().getEpoch(), event.getGossipTerm().getHeartbeat());

                membershipEvents.add(membershipEvent);

                return true;
            });

            ReceivedEvent(DirectBuffer buffer, int offset, int length)
            {
                gossipEvent.wrap(buffer, offset, length);
            }
        }

        private final List<ReceivedEvent> receivedEvents = new ArrayList<>();

        @Override
        public void onResponse(int streamId, long requestId, DirectBuffer buffer, int offset, int length)
        {
            final ReceivedEvent event = new ReceivedEvent(buffer, offset, length);
            receivedEvents.add(event);
        }

        @Override
        public void onMessage(int streamId, DirectBuffer buffer, int offset, int length)
        {
            // currently, we send no messages
        }

        @Override
        public int onFragment(DirectBuffer buffer, int offset, int length, int streamId, boolean isMarkedFailed)
        {
            final int headerLengths = TransportHeaderDescriptor.headerLength() + RequestResponseHeaderDescriptor.headerLength();

            final ReceivedEvent event = new ReceivedEvent(buffer, offset + headerLengths, length - headerLengths);
            receivedEvents.add(event);

            return FragmentHandler.CONSUME_FRAGMENT_RESULT;
        }

        public void clear()
        {
            receivedEvents.clear();
        }

        public Stream<GossipEvent> gossipEvents()
        {
            return receivedEvents.stream().map(e -> e.gossipEvent);
        }

        public Stream<MembershipEvent> membershipEvents()
        {
            return receivedEvents.stream().flatMap(e -> e.membershipEvents.stream());
        }
    }

    private static class LocalMembershipListener implements GossipMembershipListener
    {
        private final Map<String, Member> members = new HashMap<>();

        @Override
        public void onAdd(Member member)
        {
            members.put(member.getId(), member);
        }

        @Override
        public void onRemove(Member member)
        {
            members.remove(member.getId());
        }

        public boolean hasMember(String id)
        {
            return members.containsKey(id);
        }

        public Member getMember(String id)
        {
            return members.get(id);
        }
    }

}
