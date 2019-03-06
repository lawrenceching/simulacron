/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.simulacron.server;

import io.netty.channel.local.LocalAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public interface AddressResolver extends Supplier<SocketAddress> {

  byte[] defaultStartingIp = new byte[] {127, 0, 0, 1};
  int defaultStartingPort = 9042;

  // TODO: make this configurable when needed.  For now we'll just use incrementing IPs from
  // 127.0.1.1 but eventually it might be nice to have a resolver that returns incrementing ips +
  // ports when C* supports multiple instances per IP.  Also might be nice if a user wants to use a
  // different IP range or run multiple instances.
  AddressResolver defaultResolver = new Inet4Resolver();

  /**
   * A resolver that attempts to use every port (after 49152) on an ip before advancing to the next
   * ip.
   */
  AddressResolver nodePerPortResolver = new NodePerPortResolver();

  AddressResolver localAddressResolver = () -> new LocalAddress(UUID.randomUUID().toString());

  /**
   * Indicates to the resolver that the input address that was previously generated by it is no
   * longer in use and may be reused.
   *
   * @param address Address to return.
   */
  default void release(SocketAddress address) {}

  /**
   * @see com.datastax.oss.simulacron.server.Inet4Resolver
   * @deprecated replaced by {@link com.datastax.oss.simulacron.server.Inet4Resolver}
   */
  @Deprecated
  class Inet4Resolver extends com.datastax.oss.simulacron.server.Inet4Resolver {
    public Inet4Resolver(byte[] startingAddress) {
      super(startingAddress, defaultStartingPort);
    }

    public Inet4Resolver() {
      super();
    }

    public Inet4Resolver(int port) {
      super(port);
    }

    public Inet4Resolver(byte startingAddress[], int port) {
      super(startingAddress, port);
    }
  }
}
