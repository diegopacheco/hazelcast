/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
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
 *
 */

package com.hazelcast.impl;

import com.hazelcast.cluster.*;
import com.hazelcast.config.Config;
import com.hazelcast.config.Join;
import com.hazelcast.config.ListenerConfig;
import com.hazelcast.core.InstanceListener;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.impl.ascii.TextCommandService;
import com.hazelcast.impl.ascii.TextCommandServiceImpl;
import com.hazelcast.impl.base.CpuUtilization;
import com.hazelcast.impl.base.NodeInitializer;
import com.hazelcast.impl.base.NodeInitializerFactory;
import com.hazelcast.impl.base.VersionCheck;
import com.hazelcast.impl.management.ManagementCenterService;
import com.hazelcast.impl.wan.WanReplicationService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingServiceImpl;
import com.hazelcast.nio.*;
import com.hazelcast.partition.MigrationListener;
import com.hazelcast.security.Credentials;
import com.hazelcast.security.SecurityContext;
import com.hazelcast.util.ConcurrentHashSet;
import com.hazelcast.util.SimpleBoundedQueue;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class Node {
    private final ILogger logger;

    //private volatile boolean joined = false;
    private AtomicBoolean joined = new AtomicBoolean(false);

    private volatile boolean active = false;

    private volatile boolean outOfMemory = false;

    private volatile boolean completelyShutdown = false;

    private final ClusterImpl clusterImpl;

    private final Set<Address> failedConnections = new ConcurrentHashSet<Address>();

    private final NodeShutdownHookThread shutdownHookThread = new NodeShutdownHookThread("hz.ShutdownThread");

    private final boolean liteMember;

    private final NodeType localNodeType;

    final NodeBaseVariables baseVariables;

    public final ConcurrentMapManager concurrentMapManager;

    public final BlockingQueueManager blockingQueueManager;

    public final ClusterManager clusterManager;

    public final TopicManager topicManager;

    public final ListenerManager listenerManager;

    public final ClusterService clusterService;

    public final ExecutorManager executorManager;

    public final MulticastService multicastService;

    public final ConnectionManager connectionManager;

    public final ClientService clientService;

    public final TextCommandServiceImpl textCommandService;

    public final Config config;

    public final GroupProperties groupProperties;

    public final ThreadGroup threadGroup;

    final Address address;

    final MemberImpl localMember;

    volatile Address masterAddress = null;

    volatile Thread serviceThread = null;

    public final FactoryImpl factory;

    private final int buildNumber;

    public final LoggingServiceImpl loggingService;

    private final static AtomicInteger counter = new AtomicInteger();

    private final CpuUtilization cpuUtilization = new CpuUtilization();

    final SimpleBoundedQueue<Packet> serviceThreadPacketQueue = new SimpleBoundedQueue<Packet>(1000);

    final int id;

    final WanReplicationService wanReplicationService;

    final Joiner joiner;

    public final NodeInitializer initializer;

    private ManagementCenterService managementCenterService = null;

    public final SecurityContext securityContext;

    public Node(FactoryImpl factory, Config config) {
        this.id = counter.incrementAndGet();
        this.threadGroup = new ThreadGroup(factory.getName());
        this.factory = factory;
        this.config = config;
        this.groupProperties = new GroupProperties(config);
        this.liteMember = config.isLiteMember();
        this.localNodeType = (liteMember) ? NodeType.LITE_MEMBER : NodeType.MEMBER;
        ServerSocketChannel serverSocketChannel = null;
        Address localAddress = null;
        try {
            final String preferIPv4Stack = System.getProperty("java.net.preferIPv4Stack");
            final String preferIPv6Address = System.getProperty("java.net.preferIPv6Addresses");
            if (preferIPv6Address == null && preferIPv4Stack == null) {
                System.setProperty("java.net.preferIPv4Stack", "true");
            }
            serverSocketChannel = ServerSocketChannel.open();
            AddressPicker addressPicker = new AddressPicker(this, serverSocketChannel);
            localAddress = addressPicker.pickAddress();
            localAddress.setThisAddress(true);
        } catch (Throwable e) {
            Util.throwUncheckedException(e);
        }
        address = localAddress;
        localMember = new MemberImpl(address, true, localNodeType);
        String loggingType = groupProperties.LOGGING_TYPE.getString();
        this.loggingService = new LoggingServiceImpl(config.getGroupConfig().getName(), loggingType, localMember);
        this.logger = loggingService.getLogger(Node.class.getName());
        initializer = NodeInitializerFactory.create();
        initializer.beforeInitialize(this);
        securityContext = config.getSecurityConfig().isEnabled() ? initializer.getSecurityContext() : null;
        clusterImpl = new ClusterImpl(this, localMember);
        baseVariables = new NodeBaseVariables(address, localMember);
        //initialize managers..
        clusterService = new ClusterService(this);
        clusterService.start();
        connectionManager = new ConnectionManager(new NodeIOService(this), serverSocketChannel);
        clusterManager = new ClusterManager(this);
        executorManager = new ExecutorManager(this);
        clientService = new ClientService(this);
        concurrentMapManager = new ConcurrentMapManager(this);
        blockingQueueManager = new BlockingQueueManager(this);
        listenerManager = new ListenerManager(this);
        topicManager = new TopicManager(this);
        textCommandService = new TextCommandServiceImpl(this);
        clusterManager.addMember(false, localMember);
        initializer.printNodeInfo(this);
        buildNumber = initializer.getBuildNumber();
        VersionCheck.check(this, initializer.getBuild(), initializer.getVersion());
        Join join = config.getNetworkConfig().getJoin();
        MulticastService mcService = null;
        try {
            if (join.getMulticastConfig().isEnabled()) {
                MulticastSocket multicastSocket = new MulticastSocket(null);
                multicastSocket.setReuseAddress(true);
                // bind to receive interface
                multicastSocket.bind(new InetSocketAddress(
                        join.getMulticastConfig().getMulticastPort()));
                multicastSocket.setTimeToLive(32);
                // set the send interface
                multicastSocket.setInterface(address.getInetAddress());
                multicastSocket.setReceiveBufferSize(64 * 1024);
                multicastSocket.setSendBufferSize(64 * 1024);
                String multicastGroup = System.getProperty("hazelcast.multicast.group");
                if (multicastGroup == null) {
                    multicastGroup = join.getMulticastConfig().getMulticastGroup();
                }
                join.getMulticastConfig().setMulticastGroup(multicastGroup);
                multicastSocket.joinGroup(InetAddress.getByName(multicastGroup));
                multicastSocket.setSoTimeout(1000);
                mcService = new MulticastService(this, multicastSocket);
                mcService.addMulticastListener(new NodeMulticastListener(this));
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
        this.multicastService = mcService;
        wanReplicationService = new WanReplicationService(this);
        initializeListeners(config);
        joiner = createJoiner();
    }

    private void initializeListeners(Config config) {
        for (final ListenerConfig listenerCfg : config.getListenerConfigs()) {
            Object listener = listenerCfg.getImplementation();
            if (listener == null) {
                try {
                    listener = Serializer.newInstance(Serializer.loadClass(listenerCfg.getClassName()));
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                }
            }
            if (listener instanceof InstanceListener) {
                factory.addInstanceListener((InstanceListener) listener);
            } else if (listener instanceof MembershipListener) {
                clusterImpl.addMembershipListener((MembershipListener) listener);
            } else if (listener instanceof MigrationListener) {
                concurrentMapManager.partitionServiceImpl.addMigrationListener((MigrationListener) listener);
            } else if (listener != null) {
                final String error = "Unknown listener type: " + listener.getClass();
                Throwable t = new IllegalArgumentException(error);
                logger.log(Level.WARNING, error, t);
            }
        }
    }

    public void failedConnection(Address address) {
        logger.log(Level.FINEST, getThisAddress() + " failed connecting to " + address);
        failedConnections.add(address);
    }

    public ClusterImpl getClusterImpl() {
        return clusterImpl;
    }

    public final NodeType getLocalNodeType() {
        return localNodeType;
    }

    public Address getMasterAddress() {
        return masterAddress;
    }

    public Address getThisAddress() {
        return address;
    }

    public String getName() {
        return factory.getName();
    }

    public String getThreadPoolNamePrefix(String poolName) {
        return "hz." + id + ".threads." + getName() + "." + poolName + ".thread-";
    }

    public void handleInterruptedException(Thread thread, Exception e) {
        logger.log(Level.FINEST, thread.getName() + " is interrupted ", e);
    }

    public void checkNodeState() {
        if (factory.restarted) {
            throw new IllegalStateException("Hazelcast Instance is restarted!");
        } else if (!isActive()) {
            throw new IllegalStateException("Hazelcast Instance is not active!");
        }
    }

    public final boolean isLiteMember() {
        return liteMember;
    }

    public boolean joined() {
        return joined.get();
    }

    public boolean isMaster() {
        return address != null && address.equals(masterAddress);
    }

    public void setMasterAddress(final Address master) {
        if (master != null) {
            logger.log(Level.FINE, "** setting master address to " + master.toString());
        }
        masterAddress = master;
    }

    public void cleanupServiceThread() {
        clusterManager.checkServiceThread();
        baseVariables.qServiceThreadPacketCache.clear();
        concurrentMapManager.reset();
        clusterManager.stop();
    }

    private void generateMemberUuid() {
        final String uuid = UUID.randomUUID().toString();
        logger.log(Level.FINEST, "Generated new UUID for local member: " + uuid);
        localMember.setUuid(uuid);
    }

    public void shutdown(final boolean force, final boolean now) {
        if (now) {
            doShutdown(force);
        } else {
            new Thread(new Runnable() {
                public void run() {
                    doShutdown(force);
                }
            }).start();
        }
    }

    private void doShutdown(boolean force) {
        long start = System.currentTimeMillis();
        logger.log(Level.FINE, "** we are being asked to shutdown when active = " + String.valueOf(active));
        while (!force && isActive() && concurrentMapManager.partitionManager.hasActiveBackupTask()) {
            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
        if (isActive()) {
            // set the joined=false first so that
            // threads do not process unnecessary
            // events, such as remove address
            joined.set(false);
            setActive(false);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
            } catch (Throwable ignored) {
            }
            if (managementCenterService != null) {
                managementCenterService.shutdown();
            }
            logger.log(Level.FINEST, "Shutting down the clientService");
            clientService.shutdown();
            logger.log(Level.FINEST, "Shutting down the cluster service");
            concurrentMapManager.shutdown();
            clusterService.stop();
            logger.log(Level.FINEST, "Shutting down the query service");
            if (multicastService != null) {
                multicastService.stop();
            }
            logger.log(Level.FINEST, "Shutting down the connection manager");
            connectionManager.shutdown();
            logger.log(Level.FINEST, "Shutting down the concurrentMapManager");
            logger.log(Level.FINEST, "Shutting down the executorManager");
            executorManager.stop();
            textCommandService.stop();
            masterAddress = null;
            if (securityContext != null) {
                securityContext.destroy();
            }
            initializer.destroy();
            logger.log(Level.FINEST, "Shutting down the cluster manager");
            int numThreads = threadGroup.activeCount();
            Thread[] threads = new Thread[numThreads * 2];
            numThreads = threadGroup.enumerate(threads, false);
            for (int i = 0; i < numThreads; i++) {
                Thread thread = threads[i];
                logger.log(Level.FINEST, "Shutting down thread " + thread.getName());
                thread.interrupt();
            }
            logger.log(Level.INFO, "Hazelcast Shutdown is completed in " + (System.currentTimeMillis() - start) + " ms.");
            failedConnections.clear();
            serviceThreadPacketQueue.clear();
        }
    }

    public void start() {
        logger.log(Level.FINEST, "We are asked to start and completelyShutdown is " + String.valueOf(completelyShutdown));
        if (completelyShutdown) return;
        generateMemberUuid();
        final String prefix = "hz." + this.id + ".";
        serviceThread = new Thread(threadGroup, clusterService, prefix + "ServiceThread");
        serviceThread.setPriority(groupProperties.SERVICE_THREAD_PRIORITY.getInteger());
        logger.log(Level.FINEST, "Starting thread " + serviceThread.getName());
        serviceThread.start();
        connectionManager.start();
        if (config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()) {
            final Thread multicastServiceThread = new Thread(threadGroup, multicastService, prefix + "MulticastThread");
            multicastServiceThread.start();
        }
        setActive(true);
        if (!completelyShutdown) {
            logger.log(Level.FINEST, "Adding ShutdownHook");
            Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        }
        logger.log(Level.FINEST, "finished starting threads, calling join");
        join();
        int clusterSize = clusterImpl.getMembers().size();
        if (address.getPort() >= config.getPort() + clusterSize) {
            StringBuilder sb = new StringBuilder("Config seed port is ");
            sb.append(config.getPort());
            sb.append(" and cluster size is ");
            sb.append(clusterSize);
            sb.append(". Some of the ports seem occupied!");
            logger.log(Level.WARNING, sb.toString());
        }
        if (groupProperties.MANCENTER_ENABLED.getBoolean()) {
            try {
                managementCenterService = new ManagementCenterService(factory);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "ManagementCenterService could not be started!", e);
            }
        }
        initializer.afterInitialize(this);
    }

    public void onRestart() {
        generateMemberUuid();
    }

    public ILogger getLogger(String name) {
        return loggingService.getLogger(name);
    }

    public GroupProperties getGroupProperties() {
        return groupProperties;
    }

    public TextCommandService getTextCommandService() {
        return textCommandService;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public void onOutOfMemory(OutOfMemoryError e) {
        try {
            if (connectionManager != null) {
                connectionManager.shutdown();
                shutdown(true, false);
            }
        } catch (Throwable ignored) {
            logger.log(Level.FINEST, ignored.getMessage(), ignored);
        } finally {
            // Node.doShutdown sets active=false
            // active = false;
            outOfMemory = true;
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    public Set<Address> getFailedConnections() {
        return failedConnections;
    }

    public class NodeShutdownHookThread extends Thread {

        NodeShutdownHookThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            try {
                if (isActive() && !completelyShutdown) {
                    completelyShutdown = true;
                    if (groupProperties.SHUTDOWNHOOK_ENABLED.getBoolean()) {
                        shutdown(false, true);
                    }
                } else {
                    logger.log(Level.FINEST, "shutdown hook - we are not --> active and not completely down so we are not calling shutdown");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    public void setJoined() {
        joined.set(true);
    }

    public JoinInfo createJoinInfo() {
        return createJoinInfo(false);
    }

    public JoinInfo createJoinInfo(boolean withCredentials) {
        final JoinInfo jr = new JoinInfo(this.getLogger(JoinInfo.class.getName()), true, address, config, getLocalNodeType(),
                Packet.PACKET_VERSION, buildNumber, clusterImpl.getMembers().size(), 0, localMember.getUuid());
        if (withCredentials && securityContext != null) {
            Credentials c = securityContext.getCredentialsFactory().newCredentials();
            jr.setCredentials(c);
        }
        return jr;
    }

    public boolean validateJoinRequest(JoinRequest joinRequest) throws Exception {
        boolean valid = Packet.PACKET_VERSION == joinRequest.packetVersion &&
                buildNumber == joinRequest.buildNumber;
        if (valid) {
            try {
                valid = config.isCompatible(joinRequest.config);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Invalid join request, reason:" + e.getMessage());
                throw e;
            }
        }
        return valid;
    }

    void rejoin() {
        masterAddress = null;
        joined.set(false);
        clusterImpl.reset();
        failedConnections.clear();
        join();
    }

    void join() {
        try {
            if (joiner == null) {
                logger.log(Level.WARNING, "No join method is enabled! Starting standalone.");
                setAsMaster();
            } else {
                joiner.join(joined);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, e.getMessage());
            factory.lifecycleService.restart();
        }
    }

    Joiner getJoiner() {
        return joiner;
    }

    Joiner createJoiner() {
        Join join = config.getNetworkConfig().getJoin();
        if (join.getMulticastConfig().isEnabled() && multicastService != null) {
            return new MulticastJoiner(this);
        } else if (join.getTcpIpConfig().isEnabled()) {
            return new TcpIpJoiner(this);
        } else if (join.getAwsConfig().isEnabled()) {
            try {
                Class clazz = Class.forName("com.hazelcast.impl.TcpIpJoinerOverAWS");
                Constructor constructor = clazz.getConstructor(Node.class);
                return (Joiner) constructor.newInstance(this);
            } catch (Exception e) {
                logger.log(Level.WARNING, e.getMessage());
                return null;
            }
        }
        return null;
    }

    void setAsMaster() {
        logger.log(Level.FINEST, "This node is being set as the master");
        masterAddress = address;
        logger.log(Level.FINEST, "adding member myself");
        clusterManager.enqueueAndWait(new Processable() {
            public void process() {
                clusterManager.addMember(address, getLocalNodeType(), localMember.getUuid()); // add
                // myself
                clusterImpl.setMembers(baseVariables.lsMembers);
            }
        }, 5);
        setJoined();
    }

    public Config getConfig() {
        return config;
    }

    public ExecutorManager getExecutorManager() {
        return executorManager;
    }

    /**
     * @param active the active to set
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * @return the active
     */
    public boolean isActive() {
        return active;
    }

    public boolean isOutOfMemory() {
        return outOfMemory;
    }

    public CpuUtilization getCpuUtilization() {
        return cpuUtilization;
    }

    public String toString() {
        return "Node[" + getName() + "]";
    }
}
