/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.net.InetAddresses;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.BlockTargetPair;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.CachedBlocksList;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.server.namenode.HostFileManager;
import org.apache.hadoop.hdfs.server.namenode.HostFileManager.Entry;
import org.apache.hadoop.hdfs.server.namenode.HostFileManager.EntrySet;
import org.apache.hadoop.hdfs.server.namenode.HostFileManager.MutableEntrySet;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.Namesystem;
import org.apache.hadoop.hdfs.server.protocol.*;
import org.apache.hadoop.hdfs.server.protocol.BlockRecoveryCommand.RecoveringBlock;
import org.apache.hadoop.hdfs.util.CyclicIteration;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.*;
import org.apache.hadoop.net.NetworkTopology.InvalidTopologyException;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Time;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import static org.apache.hadoop.util.Time.now;

/**
 * Manage datanodes, include decommission and other activities.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class DatanodeManager {
  static final Log LOG = LogFactory.getLog(DatanodeManager.class);

  private final Namesystem namesystem;
  private final BlockManager blockManager;
  private final HeartbeatManager heartbeatManager;
  private Daemon decommissionthread = null;

  /**
   * Stores the datanode -> block map.  
   * <p>
   * Done by storing a set of {@link DatanodeDescriptor} objects, sorted by 
   * storage id. In order to keep the storage map consistent it tracks 
   * all storages ever registered with the namenode.
   * A descriptor corresponding to a specific storage id can be
   * <ul> 
   * <li>added to the map if it is a new storage id;</li>
   * <li>updated with a new datanode started as a replacement for the old one 
   * with the same storage id; and </li>
   * <li>removed if and only if an existing datanode is restarted to serve a
   * different storage id.</li>
   * </ul> <br> 
   * <p>
   * Mapping: StorageID -> DatanodeDescriptor
   */
  private final NavigableMap<String, DatanodeDescriptor> datanodeMap
      = new TreeMap<String, DatanodeDescriptor>();

  /** Cluster network topology */
  private final NetworkTopology networktopology;

  /** Host names to datanode descriptors mapping. */
  private final Host2NodesMap host2DatanodeMap = new Host2NodesMap();

  private final DNSToSwitchMapping dnsToSwitchMapping;

  private final int defaultXferPort;
  
  private final int defaultInfoPort;

  private final int defaultInfoSecurePort;

  private final int defaultIpcPort;

  /** Read include/exclude files*/
  private final HostFileManager hostFileManager = new HostFileManager();

  /** The period to wait for datanode heartbeat.*/
  private final long heartbeatExpireInterval;
  /** Ask Datanode only up to this many blocks to delete. */
  final int blockInvalidateLimit;

  /** The interval for judging stale DataNodes for read/write */
  private final long staleInterval;
  
  /** Whether or not to avoid using stale DataNodes for reading */
  private final boolean avoidStaleDataNodesForRead;

  /**
   * Whether or not to avoid using stale DataNodes for writing.
   * Note that, even if this is configured, the policy may be
   * temporarily disabled when a high percentage of the nodes
   * are marked as stale.
   */
  private final boolean avoidStaleDataNodesForWrite;

  /**
   * When the ratio of stale datanodes reaches this number, stop avoiding 
   * writing to stale datanodes, i.e., continue using stale nodes for writing.
   */
  private final float ratioUseStaleDataNodesForWrite;
  
  /** The number of stale DataNodes */
  private volatile int numStaleNodes;
  
  /**
   * Whether or not this cluster has ever consisted of more than 1 rack,
   * according to the NetworkTopology.
   */
  private boolean hasClusterEverBeenMultiRack = false;

  private final boolean checkIpHostnameInRegistration;
  /**
   * Whether we should tell datanodes what to cache in replies to
   * heartbeat messages.
   */
  private boolean sendCachingCommands = false;

  /**
   * The number of datanodes for each software version. This list should change
   * during rolling upgrades.
   * Software version -> Number of datanodes with this version
   */
  private HashMap<String, Integer> datanodesSoftwareVersions =
    new HashMap<String, Integer>(4, 0.75f);
  
  DatanodeManager(final BlockManager blockManager, final Namesystem namesystem,
      final Configuration conf) throws IOException {
    this.namesystem = namesystem;
    this.blockManager = blockManager;
     
    networktopology = NetworkTopology.getInstance(conf);

    this.heartbeatManager = new HeartbeatManager(namesystem, blockManager, conf);

    this.defaultXferPort = NetUtils.createSocketAddr(
          conf.get(DFSConfigKeys.DFS_DATANODE_ADDRESS_KEY,
              DFSConfigKeys.DFS_DATANODE_ADDRESS_DEFAULT)).getPort();
    this.defaultInfoPort = NetUtils.createSocketAddr(
          conf.get(DFSConfigKeys.DFS_DATANODE_HTTP_ADDRESS_KEY,
              DFSConfigKeys.DFS_DATANODE_HTTP_ADDRESS_DEFAULT)).getPort();
    this.defaultInfoSecurePort = NetUtils.createSocketAddr(
        conf.get(DFSConfigKeys.DFS_DATANODE_HTTPS_ADDRESS_KEY,
            DFSConfigKeys.DFS_DATANODE_HTTPS_ADDRESS_DEFAULT)).getPort();
    this.defaultIpcPort = NetUtils.createSocketAddr(
          conf.get(DFSConfigKeys.DFS_DATANODE_IPC_ADDRESS_KEY,
              DFSConfigKeys.DFS_DATANODE_IPC_ADDRESS_DEFAULT)).getPort();
    try {
      this.hostFileManager.refresh(conf.get(DFSConfigKeys.DFS_HOSTS, ""),
        conf.get(DFSConfigKeys.DFS_HOSTS_EXCLUDE, ""));
    } catch (IOException e) {
      LOG.error("error reading hosts files: ", e);
    }

    this.dnsToSwitchMapping = ReflectionUtils.newInstance(
        conf.getClass(DFSConfigKeys.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY, 
            ScriptBasedMapping.class, DNSToSwitchMapping.class), conf);
    
    // If the dns to switch mapping supports cache, resolve network
    // locations of those hosts in the include list and store the mapping
    // in the cache; so future calls to resolve will be fast.
    if (dnsToSwitchMapping instanceof CachedDNSToSwitchMapping) {
      final ArrayList<String> locations = new ArrayList<String>();
      for (Entry entry : hostFileManager.getIncludes()) {
        if (!entry.getIpAddress().isEmpty()) {
          locations.add(entry.getIpAddress());
        }
      }
      dnsToSwitchMapping.resolve(locations);
    };

    final long heartbeatIntervalSeconds = conf.getLong(
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY,
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_DEFAULT);
    final int heartbeatRecheckInterval = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY, 
        DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_DEFAULT); // 5 minutes
    this.heartbeatExpireInterval = 2 * heartbeatRecheckInterval
        + 10 * 1000 * heartbeatIntervalSeconds;
    final int blockInvalidateLimit = Math.max(20*(int)(heartbeatIntervalSeconds),
        DFSConfigKeys.DFS_BLOCK_INVALIDATE_LIMIT_DEFAULT);
    this.blockInvalidateLimit = conf.getInt(
        DFSConfigKeys.DFS_BLOCK_INVALIDATE_LIMIT_KEY, blockInvalidateLimit);
    LOG.info(DFSConfigKeys.DFS_BLOCK_INVALIDATE_LIMIT_KEY
        + "=" + this.blockInvalidateLimit);

    this.checkIpHostnameInRegistration = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_DATANODE_REGISTRATION_IP_HOSTNAME_CHECK_KEY,
        DFSConfigKeys.DFS_NAMENODE_DATANODE_REGISTRATION_IP_HOSTNAME_CHECK_DEFAULT);
    LOG.info(DFSConfigKeys.DFS_NAMENODE_DATANODE_REGISTRATION_IP_HOSTNAME_CHECK_KEY
        + "=" + checkIpHostnameInRegistration);

    this.avoidStaleDataNodesForRead = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_AVOID_STALE_DATANODE_FOR_READ_KEY,
        DFSConfigKeys.DFS_NAMENODE_AVOID_STALE_DATANODE_FOR_READ_DEFAULT);
    this.avoidStaleDataNodesForWrite = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_AVOID_STALE_DATANODE_FOR_WRITE_KEY,
        DFSConfigKeys.DFS_NAMENODE_AVOID_STALE_DATANODE_FOR_WRITE_DEFAULT);
    this.staleInterval = getStaleIntervalFromConf(conf, heartbeatExpireInterval);
    this.ratioUseStaleDataNodesForWrite = conf.getFloat(
        DFSConfigKeys.DFS_NAMENODE_USE_STALE_DATANODE_FOR_WRITE_RATIO_KEY,
        DFSConfigKeys.DFS_NAMENODE_USE_STALE_DATANODE_FOR_WRITE_RATIO_DEFAULT);
    Preconditions.checkArgument(
        (ratioUseStaleDataNodesForWrite > 0 && 
            ratioUseStaleDataNodesForWrite <= 1.0f),
        DFSConfigKeys.DFS_NAMENODE_USE_STALE_DATANODE_FOR_WRITE_RATIO_KEY +
        " = '" + ratioUseStaleDataNodesForWrite + "' is invalid. " +
        "It should be a positive non-zero float value, not greater than 1.0f.");
  }

  private static long getStaleIntervalFromConf(Configuration conf,
      long heartbeatExpireInterval) {
    long staleInterval = conf.getLong(
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_KEY, 
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_DEFAULT);
    Preconditions.checkArgument(staleInterval > 0,
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_KEY +
        " = '" + staleInterval + "' is invalid. " +
        "It should be a positive non-zero value.");
    
    final long heartbeatIntervalSeconds = conf.getLong(
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY,
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_DEFAULT);
    // The stale interval value cannot be smaller than 
    // 3 times of heartbeat interval 
    final long minStaleInterval = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_MINIMUM_INTERVAL_KEY,
        DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_MINIMUM_INTERVAL_DEFAULT)
        * heartbeatIntervalSeconds * 1000;
    if (staleInterval < minStaleInterval) {
      LOG.warn("The given interval for marking stale datanode = "
          + staleInterval + ", which is less than "
          + DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_MINIMUM_INTERVAL_DEFAULT
          + " heartbeat intervals. This may cause too frequent changes of " 
          + "stale states of DataNodes since a heartbeat msg may be missing " 
          + "due to temporary short-term failures. Reset stale interval to " 
          + minStaleInterval + ".");
      staleInterval = minStaleInterval;
    }
    if (staleInterval > heartbeatExpireInterval) {
      LOG.warn("The given interval for marking stale datanode = "
          + staleInterval + ", which is larger than heartbeat expire interval "
          + heartbeatExpireInterval + ".");
    }
    return staleInterval;
  }
  
  void activate(final Configuration conf) {
    final DecommissionManager dm = new DecommissionManager(namesystem, blockManager);
    this.decommissionthread = new Daemon(dm.new Monitor(
        conf.getInt(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_KEY, 
                    DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_DEFAULT),
        conf.getInt(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_NODES_PER_INTERVAL_KEY, 
                    DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_NODES_PER_INTERVAL_DEFAULT)));
    decommissionthread.start();

    heartbeatManager.activate(conf);
  }

  void close() {
    if (decommissionthread != null) {
      decommissionthread.interrupt();
      try {
        decommissionthread.join(3000);
      } catch (InterruptedException e) {
      }
    }
    heartbeatManager.close();
  }

  /** @return the network topology. */
  public NetworkTopology getNetworkTopology() {
    return networktopology;
  }

  /** @return the heartbeat manager. */
  HeartbeatManager getHeartbeatManager() {
    return heartbeatManager;
  }

  /** @return the datanode statistics. */
  public DatanodeStatistics getDatanodeStatistics() {
    return heartbeatManager;
  }

  /** Sort the located blocks by the distance to the target host. */
  public void sortLocatedBlocks(final String targethost,
      final List<LocatedBlock> locatedblocks) {
    //sort the blocks
    // As it is possible for the separation of node manager and datanode, 
    // here we should get node but not datanode only .
    Node client = getDatanodeByHost(targethost);
    if (client == null) {
      List<String> hosts = new ArrayList<String> (1);
      hosts.add(targethost);
      String rName = dnsToSwitchMapping.resolve(hosts).get(0);
      if (rName != null)
        client = new NodeBase(rName + NodeBase.PATH_SEPARATOR_STR + targethost);
    }
    
    Comparator<DatanodeInfo> comparator = avoidStaleDataNodesForRead ?
        new DFSUtil.DecomStaleComparator(staleInterval) : 
        DFSUtil.DECOM_COMPARATOR;
        
    for (LocatedBlock b : locatedblocks) {
      networktopology.pseudoSortByDistance(client, b.getLocations());
      // Move decommissioned/stale datanodes to the bottom
      Arrays.sort(b.getLocations(), comparator);
    }
  }
  
  CyclicIteration<String, DatanodeDescriptor> getDatanodeCyclicIteration(
      final String firstkey) {
    return new CyclicIteration<String, DatanodeDescriptor>(
        datanodeMap, firstkey);
  }

  /** @return the datanode descriptor for the host. */
  public DatanodeDescriptor getDatanodeByHost(final String host) {
    return host2DatanodeMap.getDatanodeByHost(host);
  }

  /** @return the datanode descriptor for the host. */
  public DatanodeDescriptor getDatanodeByXferAddr(String host, int xferPort) {
    return host2DatanodeMap.getDatanodeByXferAddr(host, xferPort);
  }

  /**
   * Given datanode address or host name, returns the DatanodeDescriptor for the
   * same, or if it doesn't find the datanode, it looks for a machine local and
   * then rack local datanode, if a rack local datanode is not possible either,
   * it returns the DatanodeDescriptor of any random node in the cluster.
   *
   * @param address hostaddress:transfer address
   * @return the best match for the given datanode
   */
  DatanodeDescriptor getDatanodeDescriptor(String address) {
    DatanodeID dnId = parseDNFromHostsEntry(address);
    String host = dnId.getIpAddr();
    int xferPort = dnId.getXferPort();
    DatanodeDescriptor node = getDatanodeByXferAddr(host, xferPort);
    if (node == null) {
      node = getDatanodeByHost(host);
    }
    if (node == null) {
      String networkLocation = resolveNetworkLocation(dnId);

      // If the current cluster doesn't contain the node, fallback to
      // something machine local and then rack local.
      List<Node> rackNodes = getNetworkTopology()
                                   .getDatanodesInRack(networkLocation);
      if (rackNodes != null) {
        // Try something machine local.
        for (Node rackNode : rackNodes) {
          if (((DatanodeDescriptor) rackNode).getIpAddr().equals(host)) {
            node = (DatanodeDescriptor) rackNode;
            break;
          }
        }

        // Try something rack local.
        if (node == null && !rackNodes.isEmpty()) {
          node = (DatanodeDescriptor) (rackNodes
              .get(DFSUtil.getRandom().nextInt(rackNodes.size())));
        }
      }

      // If we can't even choose rack local, just choose any node in the
      // cluster.
      if (node == null) {
        node = (DatanodeDescriptor)getNetworkTopology()
                                   .chooseRandom(NodeBase.ROOT);
      }
    }
    return node;
  }


  /** Get a datanode descriptor given corresponding storageID */
  DatanodeDescriptor getDatanode(final String storageID) {
    return datanodeMap.get(storageID);
  }

  /**
   * Get data node by storage ID.
   * 
   * @param nodeID
   * @return DatanodeDescriptor or null if the node is not found.
   * @throws UnregisteredNodeException
   */
  public DatanodeDescriptor getDatanode(DatanodeID nodeID
      ) throws UnregisteredNodeException {
    final DatanodeDescriptor node = getDatanode(nodeID.getStorageID());
    if (node == null) 
      return null;
    if (!node.getXferAddr().equals(nodeID.getXferAddr())) {
      final UnregisteredNodeException e = new UnregisteredNodeException(
          nodeID, node);
      NameNode.stateChangeLog.fatal("BLOCK* NameSystem.getDatanode: "
                                    + e.getLocalizedMessage());
      throw e;
    }
    return node;
  }

  /** Prints information about all datanodes. */
  void datanodeDump(final PrintWriter out) {
    synchronized (datanodeMap) {
      out.println("Metasave: Number of datanodes: " + datanodeMap.size());
      for(Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator(); it.hasNext();) {
        DatanodeDescriptor node = it.next();
        out.println(node.dumpDatanode());
      }
    }
  }

  /**
   * Remove a datanode descriptor.
   * @param nodeInfo datanode descriptor.
   */
  private void removeDatanode(DatanodeDescriptor nodeInfo) {
    assert namesystem.hasWriteLock();
    heartbeatManager.removeDatanode(nodeInfo);
    blockManager.removeBlocksAssociatedTo(nodeInfo);
    networktopology.remove(nodeInfo);
    decrementVersionCount(nodeInfo.getSoftwareVersion());

    if (LOG.isDebugEnabled()) {
      LOG.debug("remove datanode " + nodeInfo);
    }
    namesystem.checkSafeMode();
  }

  /**
   * Remove a datanode
   * @throws UnregisteredNodeException 
   */
  public void removeDatanode(final DatanodeID node
      ) throws UnregisteredNodeException {
    namesystem.writeLock();
    try {
      final DatanodeDescriptor descriptor = getDatanode(node);
      if (descriptor != null) {
        removeDatanode(descriptor);
      } else {
        NameNode.stateChangeLog.warn("BLOCK* removeDatanode: "
                                     + node + " does not exist");
      }
    } finally {
      namesystem.writeUnlock();
    }
  }

  /** Remove a dead datanode. */
  void removeDeadDatanode(final DatanodeID nodeID) {
      synchronized(datanodeMap) {
        DatanodeDescriptor d;
        try {
          d = getDatanode(nodeID);
        } catch(IOException e) {
          d = null;
        }
        if (d != null && isDatanodeDead(d)) {
          NameNode.stateChangeLog.info(
              "BLOCK* removeDeadDatanode: lost heartbeat from " + d);
          removeDatanode(d);
        }
      }
  }

  /** Is the datanode dead? */
  boolean isDatanodeDead(DatanodeDescriptor node) {
    return (node.getLastUpdate() <
            (Time.now() - heartbeatExpireInterval));
  }

  /** Add a datanode. */
  void addDatanode(final DatanodeDescriptor node) {
    // To keep host2DatanodeMap consistent with datanodeMap,
    // remove  from host2DatanodeMap the datanodeDescriptor removed
    // from datanodeMap before adding node to host2DatanodeMap.
    synchronized(datanodeMap) {
      host2DatanodeMap.remove(datanodeMap.put(node.getStorageID(), node));
    }

    networktopology.add(node); // may throw InvalidTopologyException
    host2DatanodeMap.add(node);
    checkIfClusterIsNowMultiRack(node);

    if (LOG.isDebugEnabled()) {
      LOG.debug(getClass().getSimpleName() + ".addDatanode: "
          + "node " + node + " is added to datanodeMap.");
    }
  }

  /** Physically remove node from datanodeMap. */
  private void wipeDatanode(final DatanodeID node) {
    final String key = node.getStorageID();
    synchronized (datanodeMap) {
      host2DatanodeMap.remove(datanodeMap.remove(key));
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(getClass().getSimpleName() + ".wipeDatanode("
          + node + "): storage " + key 
          + " is removed from datanodeMap.");
    }
  }

  private void incrementVersionCount(String version) {
    if (version == null) {
      return;
    }
    synchronized(datanodeMap) {
      Integer count = this.datanodesSoftwareVersions.get(version);
      count = count == null ? 1 : count + 1;
      this.datanodesSoftwareVersions.put(version, count);
    }
  }

  private void decrementVersionCount(String version) {
    if (version == null) {
      return;
    }
    synchronized(datanodeMap) {
      Integer count = this.datanodesSoftwareVersions.get(version);
      if(count != null) {
        if(count > 1) {
          this.datanodesSoftwareVersions.put(version, count-1);
        } else {
          this.datanodesSoftwareVersions.remove(version);
        }
      }
    }
  }

  private boolean shouldCountVersion(DatanodeDescriptor node) {
    return node.getSoftwareVersion() != null && node.isAlive &&
      !isDatanodeDead(node);
  }

  private void countSoftwareVersions() {
    synchronized(datanodeMap) {
      HashMap<String, Integer> versionCount = new HashMap<String, Integer>();
      for(DatanodeDescriptor dn: datanodeMap.values()) {
        // Check isAlive too because right after removeDatanode(),
        // isDatanodeDead() is still true 
        if(shouldCountVersion(dn))
        {
          Integer num = versionCount.get(dn.getSoftwareVersion());
          num = num == null ? 1 : num+1;
          versionCount.put(dn.getSoftwareVersion(), num);
        }
      }
      this.datanodesSoftwareVersions = versionCount;
    }
  }

  public HashMap<String, Integer> getDatanodesSoftwareVersions() {
    synchronized(datanodeMap) {
      return new HashMap<String, Integer> (this.datanodesSoftwareVersions);
    }
  }

  /* Resolve a node's network location */
  private String resolveNetworkLocation (DatanodeID node) {
    List<String> names = new ArrayList<String>(1);
    if (dnsToSwitchMapping instanceof CachedDNSToSwitchMapping) {
      names.add(node.getIpAddr());
    } else {
      names.add(node.getHostName());
    }
    
    // resolve its network location
    List<String> rName = dnsToSwitchMapping.resolve(names);
    String networkLocation;
    if (rName == null) {
      LOG.error("The resolve call returned null! Using " + 
          NetworkTopology.DEFAULT_RACK + " for host " + names);
      networkLocation = NetworkTopology.DEFAULT_RACK;
    } else {
      networkLocation = rName.get(0);
    }
    return networkLocation;
  }

  /**
   * Remove an already decommissioned data node who is neither in include nor
   * exclude hosts lists from the the list of live or dead nodes.  This is used
   * to not display an already decommssioned data node to the operators.
   * The operation procedure of making a already decommissioned data node not
   * to be displayed is as following:
   * <ol>
   *   <li> 
   *   Host must have been in the include hosts list and the include hosts list
   *   must not be empty.
   *   </li>
   *   <li>
   *   Host is decommissioned by remaining in the include hosts list and added
   *   into the exclude hosts list. Name node is updated with the new 
   *   information by issuing dfsadmin -refreshNodes command.
   *   </li>
   *   <li>
   *   Host is removed from both include hosts and exclude hosts lists.  Name 
   *   node is updated with the new informationby issuing dfsamin -refreshNodes 
   *   command.
   *   <li>
   * </ol>
   * 
   * @param nodeList
   *          , array list of live or dead nodes.
   */
  private void removeDecomNodeFromList(final List<DatanodeDescriptor> nodeList) {
    // If the include list is empty, any nodes are welcomed and it does not
    // make sense to exclude any nodes from the cluster. Therefore, no remove.
    if (!hostFileManager.hasIncludes()) {
      return;
    }

    for (Iterator<DatanodeDescriptor> it = nodeList.iterator(); it.hasNext();) {
      DatanodeDescriptor node = it.next();
      if ((!hostFileManager.isIncluded(node)) && (!hostFileManager.isExcluded(node))
          && node.isDecommissioned()) {
        // Include list is not empty, an existing datanode does not appear
        // in both include or exclude lists and it has been decommissioned.
        // Remove it from the node list.
        it.remove();
      }
    }
  }

  /**
   * Decommission the node if it is in exclude list.
   */
  private void checkDecommissioning(DatanodeDescriptor nodeReg) { 
    // If the registered node is in exclude list, then decommission it
    if (hostFileManager.isExcluded(nodeReg)) {
      startDecommission(nodeReg);
    }
  }

  /**
   * Change, if appropriate, the admin state of a datanode to 
   * decommission completed. Return true if decommission is complete.
   */
  boolean checkDecommissionState(DatanodeDescriptor node) {
    // Check to see if all blocks in this decommissioned
    // node has reached their target replication factor.
    if (node.isDecommissionInProgress()) {
      if (!blockManager.isReplicationInProgress(node)) {
        node.setDecommissioned();
        LOG.info("Decommission complete for " + node);
      }
    }
    return node.isDecommissioned();
  }

  /** Start decommissioning the specified datanode. */
  private void startDecommission(DatanodeDescriptor node) {
    if (!node.isDecommissionInProgress() && !node.isDecommissioned()) {
      LOG.info("Start Decommissioning " + node + " with " + 
          node.numBlocks() +  " blocks");
      heartbeatManager.startDecommission(node);
      node.decommissioningStatus.setStartTime(now());
      
      // all the blocks that reside on this node have to be replicated.
      checkDecommissionState(node);
    }
  }

  /** Stop decommissioning the specified datanodes. */
  void stopDecommission(DatanodeDescriptor node) {
    if (node.isDecommissionInProgress() || node.isDecommissioned()) {
      LOG.info("Stop Decommissioning " + node);
      heartbeatManager.stopDecommission(node);
      // Over-replicated blocks will be detected and processed when 
      // the dead node comes back and send in its full block report.
      if (node.isAlive) {
        blockManager.processOverReplicatedBlocksOnReCommission(node);
      }
    }
  }

  /**
   * Generate new storage ID.
   * 
   * @return unique storage ID
   * 
   * Note: that collisions are still possible if somebody will try 
   * to bring in a data storage from a different cluster.
   */
  private String newStorageID() {
    String newID = null;
    while(newID == null) {
      newID = "DS" + Integer.toString(DFSUtil.getRandom().nextInt());
      if (datanodeMap.get(newID) != null)
        newID = null;
    }
    return newID;
  }

  /**
   * Register the given datanode with the namenode. NB: the given
   * registration is mutated and given back to the datanode.
   *
   * @param nodeReg the datanode registration
   * @throws DisallowedDatanodeException if the registration request is
   *    denied because the datanode does not match includes/excludes
   */
  public void registerDatanode(DatanodeRegistration nodeReg)
      throws DisallowedDatanodeException {
    InetAddress dnAddress = Server.getRemoteIp();
    if (dnAddress != null) {
      // Mostly called inside an RPC, update ip and peer hostname
      String hostname = dnAddress.getHostName();
      String ip = dnAddress.getHostAddress();
      if (checkIpHostnameInRegistration && !isNameResolved(dnAddress)) {
        // Reject registration of unresolved datanode to prevent performance
        // impact of repetitive DNS lookups later.
        final String message = "hostname cannot be resolved (ip="
            + ip + ", hostname=" + hostname + ")";
        LOG.warn("Unresolved datanode registration: " + message);
        throw new DisallowedDatanodeException(nodeReg, message);
      }
      // update node registration with the ip and hostname from rpc request
      nodeReg.setIpAddr(ip);
      nodeReg.setPeerHostName(hostname);
    }
    
    try {
      nodeReg.setExportedKeys(blockManager.getBlockKeys());
  
      // Checks if the node is not on the hosts list.  If it is not, then
      // it will be disallowed from registering. 
      if (!hostFileManager.isIncluded(nodeReg)) {
        throw new DisallowedDatanodeException(nodeReg);
      }
        
      NameNode.stateChangeLog.info("BLOCK* registerDatanode: from "
          + nodeReg + " storage " + nodeReg.getStorageID());
  
      DatanodeDescriptor nodeS = datanodeMap.get(nodeReg.getStorageID());
      DatanodeDescriptor nodeN = host2DatanodeMap.getDatanodeByXferAddr(
          nodeReg.getIpAddr(), nodeReg.getXferPort());
        
      if (nodeN != null && nodeN != nodeS) {
        NameNode.LOG.info("BLOCK* registerDatanode: " + nodeN);
        // nodeN previously served a different data storage, 
        // which is not served by anybody anymore.
        removeDatanode(nodeN);
        // physically remove node from datanodeMap
        wipeDatanode(nodeN);
        nodeN = null;
      }
  
      if (nodeS != null) {
        if (nodeN == nodeS) {
          // The same datanode has been just restarted to serve the same data 
          // storage. We do not need to remove old data blocks, the delta will
          // be calculated on the next block report from the datanode
          if(NameNode.stateChangeLog.isDebugEnabled()) {
            NameNode.stateChangeLog.debug("BLOCK* registerDatanode: "
                + "node restarted.");
          }
        } else {
          // nodeS is found
          /* The registering datanode is a replacement node for the existing 
            data storage, which from now on will be served by a new node.
            If this message repeats, both nodes might have same storageID 
            by (insanely rare) random chance. User needs to restart one of the
            nodes with its data cleared (or user can just remove the StorageID
            value in "VERSION" file under the data directory of the datanode,
            but this is might not work if VERSION file format has changed 
         */        
          NameNode.stateChangeLog.info("BLOCK* registerDatanode: " + nodeS
              + " is replaced by " + nodeReg + " with the same storageID "
              + nodeReg.getStorageID());
        }
        
        boolean success = false;
        try {
          // update cluster map
          getNetworkTopology().remove(nodeS);
          if(shouldCountVersion(nodeS)) {
            decrementVersionCount(nodeS.getSoftwareVersion());
          }
          nodeS.updateRegInfo(nodeReg);

          nodeS.setSoftwareVersion(nodeReg.getSoftwareVersion());
          nodeS.setDisallowed(false); // Node is in the include list

          // resolve network location
          nodeS.setNetworkLocation(resolveNetworkLocation(nodeS));
          getNetworkTopology().add(nodeS);
            
          // also treat the registration message as a heartbeat
          heartbeatManager.register(nodeS);
          incrementVersionCount(nodeS.getSoftwareVersion());
          checkDecommissioning(nodeS);
          success = true;
        } finally {
          if (!success) {
            removeDatanode(nodeS);
            wipeDatanode(nodeS);
            countSoftwareVersions();
          }
        }
        return;
      } 
  
      // this is a new datanode serving a new data storage
      if ("".equals(nodeReg.getStorageID())) {
        // this data storage has never been registered
        // it is either empty or was created by pre-storageID version of DFS
        nodeReg.setStorageID(newStorageID());
        if (NameNode.stateChangeLog.isDebugEnabled()) {
          NameNode.stateChangeLog.debug(
              "BLOCK* NameSystem.registerDatanode: "
              + "new storageID " + nodeReg.getStorageID() + " assigned.");
        }
      }
      
      DatanodeDescriptor nodeDescr 
        = new DatanodeDescriptor(nodeReg, NetworkTopology.DEFAULT_RACK);
      boolean success = false;
      try {
        nodeDescr.setNetworkLocation(resolveNetworkLocation(nodeDescr));
        networktopology.add(nodeDescr);
        nodeDescr.setSoftwareVersion(nodeReg.getSoftwareVersion());
  
        // register new datanode
        addDatanode(nodeDescr);
        checkDecommissioning(nodeDescr);
        
        // also treat the registration message as a heartbeat
        // no need to update its timestamp
        // because its is done when the descriptor is created
        heartbeatManager.addDatanode(nodeDescr);
        success = true;
        incrementVersionCount(nodeReg.getSoftwareVersion());
      } finally {
        if (!success) {
          removeDatanode(nodeDescr);
          wipeDatanode(nodeDescr);
          countSoftwareVersions();
        }
      }
    } catch (InvalidTopologyException e) {
      // If the network location is invalid, clear the cached mappings
      // so that we have a chance to re-add this DataNode with the
      // correct network location later.
      List<String> invalidNodeNames = new ArrayList<String>(3);
      // clear cache for nodes in IP or Hostname
      invalidNodeNames.add(nodeReg.getIpAddr());
      invalidNodeNames.add(nodeReg.getHostName());
      invalidNodeNames.add(nodeReg.getPeerHostName());
      dnsToSwitchMapping.reloadCachedMappings(invalidNodeNames);
      throw e;
    }
  }

  /**
   * Rereads conf to get hosts and exclude list file names.
   * Rereads the files to update the hosts and exclude lists.  It
   * checks if any of the hosts have changed states:
   */
  public void refreshNodes(final Configuration conf) throws IOException {
    refreshHostsReader(conf);
    namesystem.writeLock();
    try {
      refreshDatanodes();
      countSoftwareVersions();
    } finally {
      namesystem.writeUnlock();
    }
  }

  /** Reread include/exclude files. */
  private void refreshHostsReader(Configuration conf) throws IOException {
    // Reread the conf to get dfs.hosts and dfs.hosts.exclude filenames.
    // Update the file names and refresh internal includes and excludes list.
    if (conf == null) {
      conf = new HdfsConfiguration();
    }
    this.hostFileManager.refresh(conf.get(DFSConfigKeys.DFS_HOSTS, ""),
      conf.get(DFSConfigKeys.DFS_HOSTS_EXCLUDE, ""));
  }
  
  /**
   * 1. Added to hosts  --> no further work needed here.
   * 2. Removed from hosts --> mark AdminState as decommissioned. 
   * 3. Added to exclude --> start decommission.
   * 4. Removed from exclude --> stop decommission.
   */
  private void refreshDatanodes() {
    for(DatanodeDescriptor node : datanodeMap.values()) {
      // Check if not include.
      if (!hostFileManager.isIncluded(node)) {
        node.setDisallowed(true); // case 2.
      } else {
        if (hostFileManager.isExcluded(node)) {
          startDecommission(node); // case 3.
        } else {
          stopDecommission(node); // case 4.
        }
      }
    }
  }

  /** @return the number of live datanodes. */
  public int getNumLiveDataNodes() {
    int numLive = 0;
    synchronized (datanodeMap) {
      for(DatanodeDescriptor dn : datanodeMap.values()) {
        if (!isDatanodeDead(dn) ) {
          numLive++;
        }
      }
    }
    return numLive;
  }

  /** @return the number of dead datanodes. */
  public int getNumDeadDataNodes() {
    int numDead = 0;
    synchronized (datanodeMap) {   
      for(DatanodeDescriptor dn : datanodeMap.values()) {
        if (isDatanodeDead(dn) ) {
          numDead++;
        }
      }
    }
    return numDead;
  }

  /** @return list of datanodes where decommissioning is in progress. */
  public List<DatanodeDescriptor> getDecommissioningNodes() {
    namesystem.readLock();
    try {
      final List<DatanodeDescriptor> decommissioningNodes
          = new ArrayList<DatanodeDescriptor>();
      final List<DatanodeDescriptor> results = getDatanodeListForReport(
          DatanodeReportType.LIVE);
      for(DatanodeDescriptor node : results) {
        if (node.isDecommissionInProgress()) {
          decommissioningNodes.add(node);
        }
      }
      return decommissioningNodes;
    } finally {
      namesystem.readUnlock();
    }
  }
  
  /* Getter and Setter for stale DataNodes related attributes */

  /**
   * Whether stale datanodes should be avoided as targets on the write path.
   * The result of this function may change if the number of stale datanodes
   * eclipses a configurable threshold.
   * 
   * @return whether stale datanodes should be avoided on the write path
   */
  public boolean shouldAvoidStaleDataNodesForWrite() {
    // If # stale exceeds maximum staleness ratio, disable stale
    // datanode avoidance on the write path
    return avoidStaleDataNodesForWrite &&
        (numStaleNodes <= heartbeatManager.getLiveDatanodeCount()
            * ratioUseStaleDataNodesForWrite);
  }

  /**
   * @return The time interval used to mark DataNodes as stale.
   */
  long getStaleInterval() {
    return staleInterval;
  }

  /**
   * Set the number of current stale DataNodes. The HeartbeatManager got this
   * number based on DataNodes' heartbeats.
   * 
   * @param numStaleNodes
   *          The number of stale DataNodes to be set.
   */
  void setNumStaleNodes(int numStaleNodes) {
    this.numStaleNodes = numStaleNodes;
  }
  
  /**
   * @return Return the current number of stale DataNodes (detected by
   * HeartbeatManager). 
   */
  public int getNumStaleNodes() {
    return this.numStaleNodes;
  }

  /** Fetch live and dead datanodes. */
  public void fetchDatanodes(final List<DatanodeDescriptor> live, 
      final List<DatanodeDescriptor> dead, final boolean removeDecommissionNode) {
    if (live == null && dead == null) {
      throw new HadoopIllegalArgumentException("Both live and dead lists are null");
    }

    namesystem.readLock();
    try {
      final List<DatanodeDescriptor> results =
          getDatanodeListForReport(DatanodeReportType.ALL);    
      for(DatanodeDescriptor node : results) {
        if (isDatanodeDead(node)) {
          if (dead != null) {
            dead.add(node);
          }
        } else {
          if (live != null) {
            live.add(node);
          }
        }
      }
    } finally {
      namesystem.readUnlock();
    }
    
    if (removeDecommissionNode) {
      if (live != null) {
        removeDecomNodeFromList(live);
      }
      if (dead != null) {
        removeDecomNodeFromList(dead);
      }
    }
  }

  /**
   * @return true if this cluster has ever consisted of multiple racks, even if
   *         it is not now a multi-rack cluster.
   */
  boolean hasClusterEverBeenMultiRack() {
    return hasClusterEverBeenMultiRack;
  }

  /**
   * Check if the cluster now consists of multiple racks. If it does, and this
   * is the first time it's consisted of multiple racks, then process blocks
   * that may now be misreplicated.
   * 
   * @param node DN which caused cluster to become multi-rack. Used for logging.
   */
  @VisibleForTesting
  void checkIfClusterIsNowMultiRack(DatanodeDescriptor node) {
    if (!hasClusterEverBeenMultiRack && networktopology.getNumOfRacks() > 1) {
      String message = "DN " + node + " joining cluster has expanded a formerly " +
          "single-rack cluster to be multi-rack. ";
      if (namesystem.isPopulatingReplQueues()) {
        message += "Re-checking all blocks for replication, since they should " +
            "now be replicated cross-rack";
        LOG.info(message);
      } else {
        message += "Not checking for mis-replicated blocks because this NN is " +
            "not yet processing repl queues.";
        LOG.debug(message);
      }
      hasClusterEverBeenMultiRack = true;
      if (namesystem.isPopulatingReplQueues()) {
        blockManager.processMisReplicatedBlocks();
      }
    }
  }

  /**
   * Parse a DatanodeID from a hosts file entry
   * @param hostLine of form [hostname|ip][:port]?
   * @return DatanodeID constructed from the given string
   */
  private DatanodeID parseDNFromHostsEntry(String hostLine) {
    DatanodeID dnId;
    String hostStr;
    int port;
    int idx = hostLine.indexOf(':');

    if (-1 == idx) {
      hostStr = hostLine;
      port = DFSConfigKeys.DFS_DATANODE_DEFAULT_PORT;
    } else {
      hostStr = hostLine.substring(0, idx);
      port = Integer.valueOf(hostLine.substring(idx+1));
    }

    if (InetAddresses.isInetAddress(hostStr)) {
      // The IP:port is sufficient for listing in a report
      dnId = new DatanodeID(hostStr, "", "", port,
          DFSConfigKeys.DFS_DATANODE_HTTP_DEFAULT_PORT,
          DFSConfigKeys.DFS_DATANODE_HTTPS_DEFAULT_PORT,
          DFSConfigKeys.DFS_DATANODE_IPC_DEFAULT_PORT);
    } else {
      String ipAddr = "";
      try {
        ipAddr = InetAddress.getByName(hostStr).getHostAddress();
      } catch (UnknownHostException e) {
        LOG.warn("Invalid hostname " + hostStr + " in hosts file");
      }
      dnId = new DatanodeID(ipAddr, hostStr, "", port,
          DFSConfigKeys.DFS_DATANODE_HTTP_DEFAULT_PORT,
          DFSConfigKeys.DFS_DATANODE_HTTPS_DEFAULT_PORT,
          DFSConfigKeys.DFS_DATANODE_IPC_DEFAULT_PORT);
    }
    return dnId;
  }

  /** For generating datanode reports */
  public List<DatanodeDescriptor> getDatanodeListForReport(
      final DatanodeReportType type) {
    boolean listLiveNodes = type == DatanodeReportType.ALL ||
                            type == DatanodeReportType.LIVE;
    boolean listDeadNodes = type == DatanodeReportType.ALL ||
                            type == DatanodeReportType.DEAD;

    ArrayList<DatanodeDescriptor> nodes = null;
    final MutableEntrySet foundNodes = new MutableEntrySet();
    synchronized(datanodeMap) {
      nodes = new ArrayList<DatanodeDescriptor>(datanodeMap.size());
      Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator();
      while (it.hasNext()) { 
        DatanodeDescriptor dn = it.next();
        final boolean isDead = isDatanodeDead(dn);
        if ( (isDead && listDeadNodes) || (!isDead && listLiveNodes) ) {
          nodes.add(dn);
        }
        foundNodes.add(dn);
      }
    }

    if (listDeadNodes) {
      final EntrySet includedNodes = hostFileManager.getIncludes();
      final EntrySet excludedNodes = hostFileManager.getExcludes();
      for (Entry entry : includedNodes) {
        if ((foundNodes.find(entry) == null) &&
            (excludedNodes.find(entry) == null)) {
          // The remaining nodes are ones that are referenced by the hosts
          // files but that we do not know about, ie that we have never
          // head from. Eg. an entry that is no longer part of the cluster
          // or a bogus entry was given in the hosts files
          //
          // If the host file entry specified the xferPort, we use that.
          // Otherwise, we guess that it is the default xfer port.
          // We can't ask the DataNode what it had configured, because it's
          // dead.
          DatanodeDescriptor dn =
              new DatanodeDescriptor(new DatanodeID(entry.getIpAddress(),
                  entry.getPrefix(), "",
                  entry.getPort() == 0 ? defaultXferPort : entry.getPort(),
                  defaultInfoPort, defaultInfoSecurePort, defaultIpcPort));
          dn.setLastUpdate(0); // Consider this node dead for reporting
          nodes.add(dn);
        }
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("getDatanodeListForReport with " +
          "includedNodes = " + hostFileManager.getIncludes() +
          ", excludedNodes = " + hostFileManager.getExcludes() +
          ", foundNodes = " + foundNodes +
          ", nodes = " + nodes);
    }
    return nodes;
  }
  
  /**
   * Checks if name resolution was successful for the given address.  If IP
   * address and host name are the same, then it means name resolution has
   * failed.  As a special case, local addresses are also considered
   * acceptable.  This is particularly important on Windows, where 127.0.0.1 does
   * not resolve to "localhost".
   *
   * @param address InetAddress to check
   * @return boolean true if name resolution successful or address is local
   */
  private static boolean isNameResolved(InetAddress address) {
    String hostname = address.getHostName();
    String ip = address.getHostAddress();
    return !hostname.equals(ip) || NetUtils.isLocalAddress(address);
  }
  
  private void setDatanodeDead(DatanodeDescriptor node) {
    node.setLastUpdate(0);
  }

  /** Handle heartbeat from datanodes. */
  public DatanodeCommand[] handleHeartbeat(DatanodeRegistration nodeReg,
      final String blockPoolId,
      long capacity, long dfsUsed, long remaining, long blockPoolUsed,
      long cacheCapacity, long cacheUsed, int xceiverCount, int maxTransfers,
      int failedVolumes) throws IOException {
    synchronized (heartbeatManager) {
      synchronized (datanodeMap) {
        DatanodeDescriptor nodeinfo = null;
        try {
          nodeinfo = getDatanode(nodeReg);
        } catch(UnregisteredNodeException e) {
          return new DatanodeCommand[]{RegisterCommand.REGISTER};
        }
        
        // Check if this datanode should actually be shutdown instead. 
        if (nodeinfo != null && nodeinfo.isDisallowed()) {
          setDatanodeDead(nodeinfo);
          throw new DisallowedDatanodeException(nodeinfo);
        }

        if (nodeinfo == null || !nodeinfo.isAlive) {
          return new DatanodeCommand[]{RegisterCommand.REGISTER};
        }

        heartbeatManager.updateHeartbeat(nodeinfo, capacity, dfsUsed,
            remaining, blockPoolUsed, cacheCapacity, cacheUsed, xceiverCount,
            failedVolumes);

        // If we are in safemode, do not send back any recovery / replication
        // requests. Don't even drain the existing queue of work.
        if(namesystem.isInSafeMode()) {
          return new DatanodeCommand[0];
        }

        //check lease recovery
        BlockInfoUnderConstruction[] blocks = nodeinfo
            .getLeaseRecoveryCommand(Integer.MAX_VALUE);
        if (blocks != null) {
          BlockRecoveryCommand brCommand = new BlockRecoveryCommand(
              blocks.length);
          for (BlockInfoUnderConstruction b : blocks) {
            DatanodeDescriptor[] expectedLocations = b.getExpectedLocations();
            // Skip stale nodes during recovery - not heart beated for some time (30s by default).
            List<DatanodeDescriptor> recoveryLocations =
                new ArrayList<DatanodeDescriptor>(expectedLocations.length);
            for (int i = 0; i < expectedLocations.length; i++) {
              if (!expectedLocations[i].isStale(this.staleInterval)) {
                recoveryLocations.add(expectedLocations[i]);
              }
            }
            // If we only get 1 replica after eliminating stale nodes, then choose all
            // replicas for recovery and let the primary data node handle failures.
            if (recoveryLocations.size() > 1) {
              if (recoveryLocations.size() != expectedLocations.length) {
                LOG.info("Skipped stale nodes for recovery : " +
                    (expectedLocations.length - recoveryLocations.size()));
              }
              brCommand.add(new RecoveringBlock(
                  new ExtendedBlock(blockPoolId, b),
                  recoveryLocations.toArray(new DatanodeDescriptor[recoveryLocations.size()]),
                  b.getBlockRecoveryId()));
            } else {
              // If too many replicas are stale, then choose all replicas to participate
              // in block recovery.
              brCommand.add(new RecoveringBlock(
                  new ExtendedBlock(blockPoolId, b),
                  expectedLocations,
                  b.getBlockRecoveryId()));
            }
          }
          return new DatanodeCommand[] { brCommand };
        }

        final List<DatanodeCommand> cmds = new ArrayList<DatanodeCommand>();
        //check pending replication
        List<BlockTargetPair> pendingList = nodeinfo.getReplicationCommand(
              maxTransfers);
        if (pendingList != null) {
          cmds.add(new BlockCommand(DatanodeProtocol.DNA_TRANSFER, blockPoolId,
              pendingList));
        }
        //check block invalidation
        Block[] blks = nodeinfo.getInvalidateBlocks(blockInvalidateLimit);
        if (blks != null) {
          cmds.add(new BlockCommand(DatanodeProtocol.DNA_INVALIDATE,
              blockPoolId, blks));
        }
        DatanodeCommand pendingCacheCommand =
            getCacheCommand(nodeinfo.getPendingCached(), nodeinfo,
              DatanodeProtocol.DNA_CACHE, blockPoolId);
        if (pendingCacheCommand != null) {
          cmds.add(pendingCacheCommand);
        }
        DatanodeCommand pendingUncacheCommand =
            getCacheCommand(nodeinfo.getPendingUncached(), nodeinfo,
              DatanodeProtocol.DNA_UNCACHE, blockPoolId);
        if (pendingUncacheCommand != null) {
          cmds.add(pendingUncacheCommand);
        }

        blockManager.addKeyUpdateCommand(cmds, nodeinfo);

        // check for balancer bandwidth update
        if (nodeinfo.getBalancerBandwidth() > 0) {
          cmds.add(new BalancerBandwidthCommand(nodeinfo.getBalancerBandwidth()));
          // set back to 0 to indicate that datanode has been sent the new value
          nodeinfo.setBalancerBandwidth(0);
        }

        if (!cmds.isEmpty()) {
          return cmds.toArray(new DatanodeCommand[cmds.size()]);
        }
      }
    }

    return new DatanodeCommand[0];
  }

  /**
   * Convert a CachedBlockList into a DatanodeCommand with a list of blocks.
   *
   * @param list       The {@link CachedBlocksList}.  This function 
   *                   clears the list.
   * @param datanode   The datanode.
   * @param action     The action to perform in the command.
   * @param poolId     The block pool id.
   * @return           A DatanodeCommand to be sent back to the DN, or null if
   *                   there is nothing to be done.
   */
  private DatanodeCommand getCacheCommand(CachedBlocksList list,
      DatanodeDescriptor datanode, int action, String poolId) {
    int length = list.size();
    if (length == 0) {
      return null;
    }
    // Read and clear the existing cache commands.
    long[] blockIds = new long[length];
    int i = 0;
    for (Iterator<CachedBlock> iter = list.iterator();
            iter.hasNext(); ) {
      CachedBlock cachedBlock = iter.next();
      blockIds[i++] = cachedBlock.getBlockId();
      iter.remove();
    }
    if (!sendCachingCommands) {
      // Do not send caching commands unless the FSNamesystem told us we
      // should.
      return null;
    }
    return new BlockIdCommand(action, poolId, blockIds);
  }

  /**
   * Tell all datanodes to use a new, non-persistent bandwidth value for
   * dfs.balance.bandwidthPerSec.
   *
   * A system administrator can tune the balancer bandwidth parameter
   * (dfs.datanode.balance.bandwidthPerSec) dynamically by calling
   * "dfsadmin -setBalanacerBandwidth newbandwidth", at which point the
   * following 'bandwidth' variable gets updated with the new value for each
   * node. Once the heartbeat command is issued to update the value on the
   * specified datanode, this value will be set back to 0.
   *
   * @param bandwidth Blanacer bandwidth in bytes per second for all datanodes.
   * @throws IOException
   */
  public void setBalancerBandwidth(long bandwidth) throws IOException {
    synchronized(datanodeMap) {
      for (DatanodeDescriptor nodeInfo : datanodeMap.values()) {
        nodeInfo.setBalancerBandwidth(bandwidth);
      }
    }
  }
  
  public void markAllDatanodesStale() {
    LOG.info("Marking all datandoes as stale");
    synchronized (datanodeMap) {
      for (DatanodeDescriptor dn : datanodeMap.values()) {
        dn.markStaleAfterFailover();
      }
    }
  }

  /**
   * Clear any actions that are queued up to be sent to the DNs
   * on their next heartbeats. This includes block invalidations,
   * recoveries, and replication requests.
   */
  public void clearPendingQueues() {
    synchronized (datanodeMap) {
      for (DatanodeDescriptor dn : datanodeMap.values()) {
        dn.clearBlockQueues();
      }
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + ": " + host2DatanodeMap;
  }

  public void setSendCachingCommands(boolean sendCachingCommands) {
    this.sendCachingCommands = sendCachingCommands;
  }
}
