package com.datastax.simulacron.server;

import com.datastax.oss.protocol.internal.*;
import com.datastax.oss.protocol.internal.request.*;
import com.datastax.oss.protocol.internal.response.*;
import com.datastax.oss.protocol.internal.response.Error;
import com.datastax.simulacron.common.cluster.Cluster;
import com.datastax.simulacron.common.cluster.DataCenter;
import com.datastax.simulacron.common.cluster.Node;
import com.datastax.simulacron.common.utils.FrameUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.local.LocalServerChannel;
import org.junit.After;
import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.datastax.simulacron.server.AddressResolver.localAddressResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class ServerTest {

  private final EventLoopGroup eventLoop = new DefaultEventLoopGroup();

  private final Server localServer =
      Server.builder(eventLoop, LocalServerChannel.class)
          .withAddressResolver(localAddressResolver)
          .build();

  @After
  public void tearDown() {
    eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
  }

  @Test
  public void testRegisterNode() throws Exception {
    Node node = Node.builder().build();

    // Should bind within 5 seconds and get a bound node back.
    Node boundNode = localServer.register(node).get(5, TimeUnit.SECONDS);
    assertThat(boundNode).isInstanceOf(BoundNode.class);

    // Should be wrapped and registered in a dummy cluster.
    assertThat(localServer.getClusterRegistry().get(boundNode.getCluster().getId()))
        .isSameAs(boundNode.getCluster());

    try (MockClient client = new MockClient(eventLoop)) {
      client.connect(boundNode.getAddress());
      client.write(new Startup());
      // Expect a Ready response.
      Frame response = client.next();
      assertThat(response.message).isInstanceOf(Ready.class);
    }
  }

  @Test
  public void testRegisterNodeBelongingToACluster() throws Exception {
    // attempting to register a node on its own that belongs to a cluster should fail.
    Cluster cluster = Cluster.builder().build();
    DataCenter dc = cluster.addDataCenter().build();
    Node node = dc.addNode().build();

    try {
      localServer.register(node).get(5, TimeUnit.SECONDS);
      fail();
    } catch (Exception e) {
      assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  public void testRegisterCluster() throws Exception {
    Cluster cluster = Cluster.builder().withNodes(5, 5).build();
    Cluster boundCluster = localServer.register(cluster).get(5, TimeUnit.SECONDS);

    // Cluster should be registered.
    assertThat(localServer.getClusterRegistry().get(boundCluster.getId())).isSameAs(boundCluster);

    // Should be 2 DCs.
    assertThat(boundCluster.getDataCenters()).hasSize(2);
    // Ensure an ID is assigned to each DC and Node.
    for (DataCenter dataCenter : boundCluster.getDataCenters()) {
      // Each DC has 5 nodes.
      assertThat(dataCenter.getNodes()).hasSize(5);
      assertThat(dataCenter.getId()).isNotNull();

      for (Node node : dataCenter.getNodes()) {
        // Each node should have assigned address and has an ID.
        assertThat(node.getAddress()).isNotNull();
        assertThat(node.getId()).isNotNull();

        // Each node should handle data.
        try (MockClient client = new MockClient(eventLoop)) {
          client.connect(node.getAddress());
          client.write(new Startup());
          // Expect a Ready response.
          Frame response = client.next();
          assertThat(response.message).isInstanceOf(Ready.class);
        }
      }
    }
  }

  @Test
  public void testRegisterClusterFailsWhenNodeAlreadyBound() throws Exception {
    Cluster cluster = Cluster.builder().build();
    DataCenter dc = cluster.addDataCenter().build();
    SocketAddress address = localAddressResolver.get();

    // Create 2 nodes with the same address, this should cause issue since both can't be
    // bound to same interface.
    Node node0 = dc.addNode().withAddress(address).build();
    Node node1 = dc.addNode().withAddress(address).build();

    try {
      localServer.register(cluster).get(5, TimeUnit.SECONDS);
      fail();
    } catch (Exception e) {
      assertThat(e.getCause()).isInstanceOf(BindNodeException.class);
      BindNodeException bne = (BindNodeException) e.getCause();
      assertThat(bne.getAddress()).isSameAs(address);
      assertThat(bne.getNode()).isIn(node0, node1);
      assertThat(localServer.getClusterRegistry()).doesNotContainKey(cluster.getId());
    }

    // Cluster should not have been registered.
    assertThat(localServer.getClusterRegistry()).isEmpty();
  }

  /** A custom handler that delays binding of a socket by 1 second for the given address. */
  @ChannelHandler.Sharable
  class SlowBindHandler extends ChannelOutboundHandlerAdapter {

    SocketAddress slowAddr;

    SlowBindHandler(SocketAddress slowAddr) {
      this.slowAddr = slowAddr;
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
        throws Exception {
      if (localAddress == slowAddr) {
        // delay binding 1 second.
        eventLoop.schedule(
            () -> {
              try {
                super.bind(ctx, localAddress, promise);
              } catch (Exception e) {
                // shouldn't happen.
              }
            },
            1,
            TimeUnit.SECONDS);
      } else {
        super.bind(ctx, localAddress, promise);
      }
    }
  }

  @Test
  public void testRegisterClusterFailsWhenBindTimesOut() throws Exception {
    // Designated address to be slow to bind.
    SocketAddress slowAddr = localAddressResolver.get();

    // create a bootstrap with a handler that delays binding by 1 second for designated address.
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(eventLoop)
            .channel(LocalServerChannel.class)
            .handler(new SlowBindHandler(slowAddr))
            .childHandler(new Server.Initializer());

    // Define server with 500ms timeout, which should cause binding of slow address to timeout and fail register.
    Server flakyServer =
        new Server.Builder(serverBootstrap)
            .withAddressResolver(localAddressResolver)
            .withBindTimeout(500, TimeUnit.MILLISECONDS)
            .build();

    // Create a 2 node cluster with 1 node having the slow address.
    Cluster cluster = Cluster.builder().build();
    DataCenter dc = cluster.addDataCenter().build();
    dc.addNode().withAddress(slowAddr).build();
    dc.addNode().build();

    // Attempt to register which should fail.
    try {
      flakyServer.register(cluster).get(5, TimeUnit.SECONDS);
      fail();
    } catch (Exception e) {
      // Expect a timeout exception.
      assertThat(e.getCause()).isInstanceOf(TimeoutException.class);
    }
  }

  @Test
  public void testUnregisterCluster() throws Exception {
    Cluster cluster = Cluster.builder().withNodes(2, 2).build();
    Cluster boundCluster = localServer.register(cluster).get(5, TimeUnit.SECONDS);

    // Cluster should be registered.
    assertThat(localServer.getClusterRegistry().get(boundCluster.getId())).isSameAs(boundCluster);

    // Should be 4 nodes total.
    List<Node> nodes = boundCluster.getNodes();
    assertThat(nodes).hasSize(4);
    for (Node node : nodes) {
      // Each node's channel should be open.
      assertThat(((BoundNode) node).channel.get().isOpen()).isTrue();
    }

    // Unregistering the cluster should close each nodes channel and remove cluster.
    assertThat(localServer.unregister(boundCluster.getId()).get(5, TimeUnit.SECONDS))
        .isSameAs(boundCluster);

    // Cluster should be removed from registry.
    assertThat(localServer.getClusterRegistry()).doesNotContainKey(boundCluster.getId());

    // All node's channels should be closed.
    for (Node node : nodes) {
      // Each node's channel should be open.
      assertThat(((BoundNode) node).channel.get().isOpen()).isFalse();
    }
  }

  @Test
  public void testUnregisterClusterWithoutId() throws Exception {
    // attempting to unregister using a Cluster without an assigned ID should thrown an exception.
    Cluster cluster = Cluster.builder().withNodes(2, 2).build();
    try {
      localServer.unregister(cluster.getId()).get(5, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  public void testUnregisterClusterNotRegistered() throws Exception {
    // attemping to unregister a Cluster that is not registered should throw an exception.
    Cluster cluster = Cluster.builder().withId(Long.MAX_VALUE).withNodes(1).build();
    try {
      localServer.unregister(cluster.getId()).get(5, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
      assertThat(localServer.getClusterRegistry()).doesNotContainKey(cluster.getId());
    }
  }

  @Test
  public void testUnregisterNodeWithoutCluster() throws Exception {
    // attempting to unregister a Node that has no parent cluster should throw an exception.
    Node node = Node.builder().build();
    try {
      localServer.unregister(node.getId()).get(5, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  public void testShouldStopAcceptingStartupAndAcceptAgain() throws Exception {
    Node node = Node.builder().build();
    BoundNode boundNode = (BoundNode) localServer.register(node).get(5, TimeUnit.SECONDS);

    // Should be wrapped and registered in a dummy cluster.
    assertThat(localServer.getClusterRegistry().get(boundNode.getCluster().getId()))
        .isSameAs(boundNode.getCluster());

    try (MockClient client = new MockClient(eventLoop)) {
      client.connect(boundNode.getAddress());
      client.write(new Startup());
      // Expect a Ready response.
      Frame response = client.next();
      assertThat(response.message).isInstanceOf(Ready.class);

      boundNode
          .rejectNewConnections(-1, BoundNode.RejectScope.REJECT_STARTUP)
          .get(5, TimeUnit.SECONDS);

      // client should remain connected.
      assertThat(client.channel.isOpen()).isTrue();

      // New client should open connection, but fail to get response to startup since not listening.
      try (MockClient client2 = new MockClient(eventLoop)) {
        client2.connect(boundNode.getAddress());
        client2.write(new Startup());
        // Expect a Ready response.
        response = client2.nextQuick();
        assertThat(response).isNull();
      }

      // Start accepting new connections again.
      boundNode.acceptNewConnections().get(5, TimeUnit.SECONDS);

      // New client should open connection and receive 'Ready' to 'Startup' request.
      try (MockClient client3 = new MockClient(eventLoop)) {
        client3.connect(boundNode.getAddress());
        client3.write(new Startup());
        // Expect a Ready response.
        response = client3.next();
        assertThat(response.message).isInstanceOf(Ready.class);
      }
    }
  }

  @Test
  public void testShouldStopAcceptingConnectionsAndAcceptAgain() throws Exception {
    Node node = Node.builder().build();
    BoundNode boundNode = (BoundNode) localServer.register(node).get(5, TimeUnit.SECONDS);

    // Should be wrapped and registered in a dummy cluster.
    assertThat(localServer.getClusterRegistry().get(boundNode.getCluster().getId()))
        .isSameAs(boundNode.getCluster());

    try (MockClient client = new MockClient(eventLoop)) {
      client.connect(boundNode.getAddress());
      client.write(new Startup());
      // Expect a Ready response.
      Frame response = client.next();
      assertThat(response.message).isInstanceOf(Ready.class);

      boundNode.rejectNewConnections(-1, BoundNode.RejectScope.UNBIND).get(5, TimeUnit.SECONDS);

      // client should remain connected.
      assertThat(client.channel.isOpen()).isTrue();

      // New client should not be able to open connection
      try (MockClient client2 = new MockClient(eventLoop)) {
        try {
          client2.connect(boundNode.getAddress());
          fail("Did not expect to be able to connect");
        } catch (ConnectException ce) { // Expected
        }
      }

      // Start accepting new connections again.
      boundNode.acceptNewConnections().get(5, TimeUnit.SECONDS);

      // New client should open connection and receive 'Ready' to 'Startup' request.
      try (MockClient client3 = new MockClient(eventLoop)) {
        client3.connect(boundNode.getAddress());
        client3.write(new Startup());
        // Expect a Ready response.
        response = client3.next();
        assertThat(response.message).isInstanceOf(Ready.class);
      }
    }
  }

  @Test
  public void testShouldStopAcceptingConnectionsAfter5() throws Exception {
    Node node = Node.builder().build();
    BoundNode boundNode = (BoundNode) localServer.register(node).get(5, TimeUnit.SECONDS);

    // Should be wrapped and registered in a dummy cluster.
    assertThat(localServer.getClusterRegistry().get(boundNode.getCluster().getId()))
        .isSameAs(boundNode.getCluster());

    try (MockClient client = new MockClient(eventLoop)) {
      client.connect(boundNode.getAddress());
      client.write(new Startup());
      // Expect a Ready response.
      Frame response = client.next();
      assertThat(response.message).isInstanceOf(Ready.class);

      boundNode.rejectNewConnections(5, BoundNode.RejectScope.UNBIND).get(5, TimeUnit.SECONDS);

      // client should remain connected.
      assertThat(client.channel.isOpen()).isTrue();

      // open 5 connections, they should all be successful
      for (int i = 0; i < 5; i++) {
        try (MockClient client2 = new MockClient(eventLoop)) {
          client2.connect(boundNode.getAddress());
          client2.write(new Startup());
          // Expect a Ready response.
          response = client2.next();
          assertThat(response.message).isInstanceOf(Ready.class);
        }
      }

      // on 5th attempt listener should be unbound, so new connections should not work.
      try (MockClient client2 = new MockClient(eventLoop)) {
        try {
          client2.connect(boundNode.getAddress());
          fail("Did not expect to be able to connect");
        } catch (ConnectException ce) { // Expected
        }
      }
    }
  }

  @Test
  public void testShouldReturnProtocolErrorWhenUsingUnsupportedProtocolVersion() throws Exception {
    // If connecting with a newer protocol version than simulacron supports, a protocol error should be sent back.
    Node node = Node.builder().build();
    BoundNode boundNode = (BoundNode) localServer.register(node).get(5, TimeUnit.SECONDS);

    // Create encoders/decoders for protocol v6.
    FrameCodec.CodecGroup v6Codecs =
        registry -> {
          registry
              .addEncoder(new AuthResponse.Codec(6))
              .addEncoder(new Batch.Codec(6))
              .addEncoder(new Execute.Codec(6))
              .addEncoder(new Options.Codec(6))
              .addEncoder(new Prepare.Codec(6))
              .addEncoder(new Query.Codec(6))
              .addEncoder(new Register.Codec(6))
              .addEncoder(new Startup.Codec(6));

          registry
              .addDecoder(new AuthChallenge.Codec(6))
              .addDecoder(new Authenticate.Codec(6))
              .addDecoder(new AuthSuccess.Codec(6))
              .addDecoder(new com.datastax.oss.protocol.internal.response.Error.Codec(6))
              .addDecoder(new Event.Codec(6))
              .addDecoder(new Ready.Codec(6))
              .addDecoder(new Result.Codec(6))
              .addDecoder(new Supported.Codec(6));
        };

    FrameCodec<ByteBuf> frameCodec =
        new FrameCodec<>(
            new ByteBufCodec(),
            Compressor.none(),
            new ProtocolV3ClientCodecs(),
            new ProtocolV4ClientCodecs(),
            new ProtocolV5ClientCodecs(),
            v6Codecs);

    try (MockClient client = new MockClient(eventLoop, frameCodec)) {
      client.connect(boundNode.getAddress());
      // Write a startup message with protocol version 6 (which is not supported)
      client.write(
          new Frame(
              6,
              false,
              0,
              false,
              null,
              FrameUtils.emptyCustomPayload,
              Collections.emptyList(),
              new Startup()));

      // Expect a protocol error indicating invalid protocol version.
      Frame response = client.next();
      assertThat(response.message).isInstanceOf(Error.class);
      assertThat(response.protocolVersion).isEqualTo(4);
      Error err = (Error) response.message;
      assertThat(err.code).isEqualTo(ProtocolConstants.ErrorCode.PROTOCOL_ERROR);
      assertThat(err.message).isEqualTo("Invalid or unsupported protocol version");

      // Try again with protocol version 4, which is supported, this should work on the same connection
      // since the previous message was simply discarded.
      client.write(new Startup());

      // Expect a Ready response.
      response = client.next();
      assertThat(response.message).isInstanceOf(Ready.class);
    }
  }
}
