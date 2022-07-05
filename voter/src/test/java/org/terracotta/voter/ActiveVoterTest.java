/*
 * Copyright Terracotta, Inc.
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

package org.terracotta.voter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static java.util.Optional.empty;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.terracotta.utilities.test.matchers.Eventually.within;

public class ActiveVoterTest {

  private static final String VOTER_ID = UUID.randomUUID().toString();
  private static final long TOPOLOGY_FETCH_INTERVAL = 10000L;

  private static final String HOST1 = "localhost:1111";
  private static final String HOST2 = "localhost:2222";
  private static final String HOST3 = "localhost:3333";
  private static final String HOST4 = "localhost:4444";

  @BeforeClass
  public static void beforeClass() {
    System.setProperty("org.terracotta.voter.topology.fetch.interval", "5000");
  }

  @Test
  public void testTopologyUpdate() throws TimeoutException, InterruptedException {
    Set<String> expectedTopology = new HashSet<>();
    expectedTopology.add(HOST1);
    expectedTopology.add(HOST2);

    ClientVoterManager firstClientVoterManager = mock(ClientVoterManager.class);
    ClientVoterManager otherClientVoterManager = mock(ClientVoterManager.class);
    when(firstClientVoterManager.isConnected()).thenReturn(true);
    when(otherClientVoterManager.isConnected()).thenReturn(true);
    when(firstClientVoterManager.getServerState()).thenReturn("ACTIVE-COORDINATOR");
    when(otherClientVoterManager.getServerState()).thenReturn("PASSIVE-STANDBY");
    when(firstClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTopology()).thenReturn(expectedTopology);
    when(firstClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTargetHostPort()).thenReturn(HOST1);
    when(otherClientVoterManager.getTargetHostPort()).thenReturn(HOST2);

    Function<String, ClientVoterManager> factory = hostPort -> {
      if (hostPort.equals(HOST1)) {
        return firstClientVoterManager;
      } else if (hostPort.equals(HOST2)) {
        return otherClientVoterManager;
      } else {
        ClientVoterManager mockClientVoterManager = mock(ClientVoterManager.class);
        when(mockClientVoterManager.getTargetHostPort()).thenReturn(hostPort);
        when(mockClientVoterManager.isConnected()).thenReturn(true);
        return mockClientVoterManager;
      }
    };
    try (ActiveVoter activeVoter = new ActiveVoter(VOTER_ID, new CompletableFuture<>(), empty(), factory, HOST1, HOST2)) {
      activeVoter.start();
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(2));

      // Update Topology To Add Passive
      expectedTopology.add(HOST3);
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(3));

      //Update Topology To Remove Passive
      expectedTopology.remove(HOST2);
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(2));
    }
  }

  @Test
  public void testOverLappingHostPortsWhileAddingServers() throws TimeoutException, InterruptedException {
    Set<String> expectedTopology = new HashSet<>();
    expectedTopology.add(HOST1);

    ClientVoterManager firstClientVoterManager = mock(ClientVoterManager.class);
    when(firstClientVoterManager.isConnected()).thenReturn(true);
    when(firstClientVoterManager.getServerState()).thenReturn("ACTIVE-COORDINATOR");
    when(firstClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTopology()).thenReturn(expectedTopology);
    when(firstClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTargetHostPort()).thenReturn(HOST1);

    Function<String, ClientVoterManager> factory = hostPort -> {
      if (hostPort.equals(HOST1)) {
        return firstClientVoterManager;
      } else {
        ClientVoterManager mockClientVoterManager = mock(ClientVoterManager.class);
        when(mockClientVoterManager.getTargetHostPort()).thenReturn(hostPort);
        when(mockClientVoterManager.isConnected()).thenReturn(true);
        return mockClientVoterManager;
      }
    };
    try (ActiveVoter activeVoter = new ActiveVoter(VOTER_ID, new CompletableFuture<>(), empty(), factory, HOST1)) {
      activeVoter.start();
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(1));

      // Update Topology To Add Passive
      expectedTopology.add(HOST2);
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(2));
    }
  }

  @Test
  public void testOverLappingHostPortsWhileRemovingServers() throws TimeoutException, InterruptedException {
    Set<String> expectedTopology = new HashSet<>();
    expectedTopology.add(HOST1);
    expectedTopology.add(HOST2);

    ClientVoterManager firstClientVoterManager = mock(ClientVoterManager.class);
    ClientVoterManager otherClientVoterManager = mock(ClientVoterManager.class);
    when(firstClientVoterManager.isConnected()).thenReturn(true);
    when(otherClientVoterManager.isConnected()).thenReturn(true);
    when(firstClientVoterManager.getServerState()).thenReturn("ACTIVE-COORDINATOR");
    when(otherClientVoterManager.getServerState()).thenReturn("PASSIVE-STANDBY");
    when(firstClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTopology()).thenReturn(expectedTopology);
    when(firstClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTargetHostPort()).thenReturn(HOST1);
    when(otherClientVoterManager.getTargetHostPort()).thenReturn(HOST2);

    Function<String, ClientVoterManager> factory = hostPort -> {
      if (hostPort.equals(HOST1)) {
        return firstClientVoterManager;
      } else if (hostPort.equals(HOST2)) {
        return otherClientVoterManager;
      } else {
        ClientVoterManager mockClientVoterManager = mock(ClientVoterManager.class);
        when(mockClientVoterManager.getTargetHostPort()).thenReturn(hostPort);
        when(mockClientVoterManager.isConnected()).thenReturn(true);
        return mockClientVoterManager;
      }
    };
    try (ActiveVoter activeVoter = new ActiveVoter(VOTER_ID, new CompletableFuture<>(), empty(), factory, HOST1, HOST2)) {
      activeVoter.start();

      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(2));

      // Update Topology To Remove Passive
      expectedTopology.remove(HOST2);
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(1));
    }
  }

  @Test
  public void testWhenStaticPassivePortsRemoved() throws TimeoutException, InterruptedException {
    Set<String> expectedTopology = new HashSet<>();
    expectedTopology.add(HOST1);
    expectedTopology.add(HOST3);
    expectedTopology.add(HOST4);

    ClientVoterManager firstClientVoterManager = mock(ClientVoterManager.class);
    ClientVoterManager otherClientVoterManager = mock(ClientVoterManager.class);
    when(firstClientVoterManager.isConnected()).thenReturn(true);
    when(otherClientVoterManager.isConnected()).thenReturn(true);
    when(firstClientVoterManager.getServerState()).thenReturn("ACTIVE-COORDINATOR");
    when(otherClientVoterManager.getServerState()).thenReturn("PASSIVE-STANDBY");
    when(firstClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.registerVoter(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTopology()).thenReturn(expectedTopology);
    when(firstClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(otherClientVoterManager.heartbeat(VOTER_ID)).thenReturn(0L);
    when(firstClientVoterManager.getTargetHostPort()).thenReturn(HOST1);
    when(otherClientVoterManager.getTargetHostPort()).thenReturn(HOST2);

    Function<String, ClientVoterManager> factory = hostPort -> {
      if (hostPort.equals(HOST1)) {
        return firstClientVoterManager;
      } else if (hostPort.equals(HOST2)) {
        return otherClientVoterManager;
      } else {
        ClientVoterManager mockClientVoterManager = mock(ClientVoterManager.class);
        when(mockClientVoterManager.getTargetHostPort()).thenReturn(hostPort);
        when(mockClientVoterManager.isConnected()).thenReturn(true);
        return mockClientVoterManager;
      }
    };
    try (ActiveVoter activeVoter = new ActiveVoter(VOTER_ID, new CompletableFuture<>(), empty(), factory, HOST1, HOST2)) {
      activeVoter.start();
      Thread.sleep(TOPOLOGY_FETCH_INTERVAL);
      MatcherAssert.assertThat(activeVoter.getExistingTopology(), CoreMatchers.is(expectedTopology));
      MatcherAssert.assertThat(activeVoter.getHeartbeatFutures().size(), CoreMatchers.is(3));
    }
  }

  @Test
  @SuppressWarnings("serial")
  public void testReregistrationWhenAllStaticHostPortsNotAvailable() throws InterruptedException, NoSuchFieldException {
    Map<String, String> servers = new HashMap<String, String>() {
      {
        put("ACTIVE-COORDINATOR", HOST1);
        put("PASSIVE-STANDBY", HOST2);
      }
    };
    Map<String, MockedClientVoterManager> managers = Collections.synchronizedMap(
        servers.entrySet()
            .stream()
            .map((e) -> manager(e.getKey(), e.getValue(), new HashSet<>(servers.values())))
            .collect(toMap(ClientVoterManager::getTargetHostPort, identity())));

    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    listAppender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(ActiveVoter.class);
    logger.addAppender(listAppender);

    try (ActiveVoter voter = new ActiveVoter(VOTER_ID, new CompletableFuture<>(), empty(), managers::get, servers.get("ACTIVE-COORDINATOR"))) {
      voter.start();
      Thread.sleep(10000L);
      waitForLogMessage(listAppender, "New Topology detected");
      disconnectManagers(managers.values());
      waitForLogMessage(listAppender, "Attempting to re-register");
      synchronized (listAppender) {
        listAppender.list.clear();
      }
      MockedClientVoterManager passiveManager = managers.get(servers.get("PASSIVE-STANDBY"));
      passiveManager.promote();
      waitForLogMessage(listAppender, "Vote owner state: ACTIVE-COORDINATOR");
      Thread.sleep(5000L); // wait for reg retry
      verify(passiveManager, atLeastOnce()).registerVoter(eq(VOTER_ID));
    } finally {
      logger.detachAppender(listAppender);
      listAppender.stop();
    }
  }

  private void waitForLogMessage(ListAppender<ILoggingEvent> appender, String message) {
    MatcherAssert.assertThat(() -> getLogs(appender), within(Duration.ofSeconds(10))
        .matches(CoreMatchers.<ILoggingEvent>hasItem(hasProperty("formattedMessage", containsString(message)))));
  }

  private List<ILoggingEvent> getLogs(ListAppender<ILoggingEvent> appender) {
    synchronized (appender) {
      return new ArrayList<>(appender.list);
    }
  }

  private void disconnectManagers(Collection<MockedClientVoterManager> managers) {
    for (MockedClientVoterManager manager : managers) {
      manager.connected = false;
    }
  }

  private MockedClientVoterManager manager(String state, String serverAddress, Set<String> topology) {
    return spy(new MockedClientVoterManager(state, serverAddress, topology));
  }

  private static class MockedClientVoterManager implements ClientVoterManager {

    private final String serverAddress;
    private volatile String state;
    private final Set<String> topology;
    volatile boolean connected = true;

    public MockedClientVoterManager(String initialState, String serverAddress, Set<String> topology) {
      this.state = initialState;
      this.serverAddress = serverAddress;
      this.topology = topology;
    }

    void promote() {
      state = "ACTIVE-COORDINATOR";
      connected = true;
    }

    @Override
    public String getTargetHostPort() {
      return serverAddress;
    }

    @Override
    public void connect(Optional<Properties> connectionProps) {
    }

    @Override
    public String getServerState() {
      return state;
    }

    @Override
    public String getServerConfig() {
      return null;
    }

    @Override
    public Set<String> getTopology() {
      return topology;
    }

    @Override
    public void close() {
      connected = false;
    }

    @Override
    public boolean isVoting() {
      return false;
    }

    @Override
    public void zombie() {

    }

    @Override
    public boolean isConnected() {
      return connected;
    }

    @Override
    public long registerVoter(String id) {
      return connected ? HEARTBEAT_RESPONSE : INVALID_VOTER_RESPONSE;
    }

    @Override
    public long heartbeat(String id) {
      return connected ? HEARTBEAT_RESPONSE : INVALID_VOTER_RESPONSE;
    }

    @Override
    public long vote(String id, long electionTerm) {
      return connected ? HEARTBEAT_RESPONSE : INVALID_VOTER_RESPONSE;
    }

    @Override
    public boolean overrideVote(String id) {
      return false;
    }

    @Override
    public boolean deregisterVoter(String id) {
      return false;
    }

    @Override
    public long getRegisteredVoterCount() {
      return 0;
    }

    @Override
    public long getRegisteredVoterLimit() {
      return 0;
    }
  }
}
