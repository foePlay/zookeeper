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

package org.apache.zookeeper.server;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.PathTrie;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from      hashtable 映射path到DataNodes
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is       一个都是DataNodes的树
 * through the hashtable. The tree is traversed only when serializing to disk.      所有通过path的访问都是通过hashtable   序列化到disk时利用tree
 */
public class DataTree {
    private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);

    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs          存储所有的DataNode
     */
    private final ConcurrentHashMap<String, DataNode> nodes = new ConcurrentHashMap<String, DataNode>();

    private final WatchManager dataWatches = new WatchManager();

    private final WatchManager childWatches = new WatchManager();

    /** the root of zookeeper tree */
    private static final String rootZookeeper = "/";

    /** the zookeeper nodes that acts as the management and status node **/
    private static final String procZookeeper = Quotas.procZookeeper;                  //    /zookeeper

    /** this will be the string thats stored as a child of root */
    private static final String procChildZookeeper = procZookeeper.substring(1);       //     zookeeper

    /**
     * the zookeeper quota node that acts as the quota management node for
     * zookeeper
     */
    private static final String quotaZookeeper = Quotas.quotaZookeeper;                 //   /zookeeper/quota

    /** this will be the string thats stored as a child of /zookeeper */
    private static final String quotaChildZookeeper = quotaZookeeper.substring(procZookeeper.length() + 1);    //  quota

    /**
     * the zookeeper config node that acts as the config management node for
     * zookeeper
     */
    private static final String configZookeeper = ZooDefs.CONFIG_NODE;                   //    /zookeeper/config

    /** this will be the string thats stored as a child of /zookeeper */
    private static final String configChildZookeeper = configZookeeper.substring(procZookeeper.length() + 1);    //   config

    /**
     * the path trie that keeps track fo the quota nodes in this datatree
     */
    // 用于跟踪quota节点
    // zookeeper_limits zookeeper_stats
    private final PathTrie pTrie = new PathTrie();

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     */
    // sessionId --- ephemeral nodes path
    // ephemeralOwner？
    private final Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<Long, HashSet<String>>();

    /**
     * This set contains the paths of all container nodes
     */
    private final Set<String> containers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * This set contains the paths of all ttl nodes
     */
    private final Set<String> ttls = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final ReferenceCountedACLCache aclCache = new ReferenceCountedACLCache();

    @SuppressWarnings("unchecked")
    public Set<String> getEphemerals(long sessionId) {
        HashSet<String> retv = ephemerals.get(sessionId);
        if (retv == null) {
            return new HashSet<String>();
        }
        HashSet<String> cloned = null;
        synchronized (retv) {
            cloned = (HashSet<String>) retv.clone();
        }
        return cloned;
    }

    public Set<String> getContainers() {
        return new HashSet<String>(containers);
    }

    public Set<String> getTtls() {
        return new HashSet<String>(ttls);
    }

    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getWatchCount() {
        return dataWatches.size() + childWatches.size();
    }

    public int getEphemeralsCount() {
        int result = 0;
        for (HashSet<String> set : ephemerals.values()) {
            result += set.size();
        }
        return result;
    }

    /**
     * Get the size of the nodes based on path and data length.
     *
     * @return size of the data
     */
    public long approximateDataSize() {
        long result = 0;
        for (Map.Entry<String, DataNode> entry : nodes.entrySet()) {
            DataNode value = entry.getValue();
            synchronized (value) {
                result += entry.getKey().length();
                result += value.getApproximateDataSize();
            }
        }
        return result;
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    private DataNode root = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper filesystem that is the proc filesystem of zookeeper
     */
    private final DataNode procDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper/quota node for maintaining quota properties for
     * zookeeper
     */
    private final DataNode quotaDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    //  DataNode只存储当前节点的数据以及孩子节点名称，不存储孩子节点数据
    public DataTree() {
        /* Rather than fight it, let root have an alias */
        nodes.put("", root);
        nodes.put(rootZookeeper, root);                // 将root根节点加入到nodes中

        /** add the proc node and quota node */
        root.addChild(procChildZookeeper);             // 给根节点root添加孩子zookeeper
        nodes.put(procZookeeper, procDataNode);        // 将 /zookeeper添加到nodes中

        procDataNode.addChild(quotaChildZookeeper);    // 给 /zookeeper节点添加孩子quota
        nodes.put(quotaZookeeper, quotaDataNode);      // 将 /zookeeper/quota添加到nodes中

        addConfigNode();
    }

    /**
     * create a /zookeeper/config node for maintaining the configuration (membership and quorum system) info for
     * zookeeper
     */
    public void addConfigNode() {
        DataNode zookeeperZnode = nodes.get(procZookeeper);         // 获取 /zookeeper节点
        if (zookeeperZnode != null) { // should always be the case
            zookeeperZnode.addChild(configChildZookeeper);          // 给/zookeeper节点增加child config
        } else {
            assert false : "There's no /zookeeper znode - this should never happen.";
        }

        nodes.put(configZookeeper, new DataNode(new byte[0], -1L, new StatPersisted()));   //将 /zookeeper/config 加入到node
        try {
            // Reconfig node is access controlled by default (ZOOKEEPER-2014).
            setACL(configZookeeper, ZooDefs.Ids.READ_ACL_UNSAFE, -1);                   // 给/zookeeper/config增加ACL属性
        } catch (KeeperException.NoNodeException e) {
            assert false : "There's no " + configZookeeper + " znode - this should never happen.";
        }
    }

    /**
     * is the path one of the special paths owned by zookeeper.
     *
     * @param path
     *            the path to be checked
     * @return true if a special path. false if not.
     */
    //判断path是否是由zk自己创建的
    boolean isSpecialPath(String path) {
        if (rootZookeeper.equals(path) || procZookeeper.equals(path) || quotaZookeeper.equals(path) || configZookeeper.equals(path)) {
            return true;
        }
        return false;
    }

    static public void copyStatPersisted(StatPersisted from, StatPersisted to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
    }

    static public void copyStat(Stat from, Stat to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
        to.setDataLength(from.getDataLength());
        to.setNumChildren(from.getNumChildren());
    }

    /**
     * update the count of this stat datanode
     *
     * @param lastPrefix
     *            the path of the node that is quotaed.
     * @param diff
     *            the diff to be added to the count
     */
    public void updateCount(String lastPrefix, int diff) {
        String statNode = Quotas.statPath(lastPrefix);
        DataNode node = nodes.get(statNode);      //  quota stat 节点数据形式 count=-1,bytes=-1
        StatsTrack updatedStat = null;
        if (node == null) {
            // should not happen
            LOG.error("Missing count node for stat " + statNode);
            return;
        }
        synchronized (node) {
            updatedStat = new StatsTrack(new String(node.data));
            updatedStat.setCount(updatedStat.getCount() + diff);
            node.data = updatedStat.toString().getBytes();
        }
        // now check if the counts match the quota
        String quotaNode = Quotas.quotaPath(lastPrefix);
        node = nodes.get(quotaNode);
        StatsTrack thisStats = null;
        if (node == null) {
            // should not happen
            LOG.error("Missing count node for quota " + quotaNode);
            return;
        }
        synchronized (node) {
            thisStats = new StatsTrack(new String(node.data));   //得到配额限制
        }
        if (thisStats.getCount() > -1 && (thisStats.getCount() < updatedStat.getCount())) {
            LOG.warn("Quota exceeded: " + lastPrefix + " count=" + updatedStat.getCount() + " limit=" + thisStats.getCount());
        }
    }

    /**
     * update the count of bytes of this stat datanode
     *
     * @param lastPrefix
     *            the path of the node that is quotaed
     * @param diff
     *            the diff to added to number of bytes
     * @throws IOException
     *             if path is not found
     */
    public void updateBytes(String lastPrefix, long diff) {
        String statNode = Quotas.statPath(lastPrefix);
        DataNode node = nodes.get(statNode);
        if (node == null) {
            // should never be null but just to make
            // findbugs happy
            LOG.error("Missing stat node for bytes " + statNode);
            return;
        }
        StatsTrack updatedStat = null;
        synchronized (node) {
            updatedStat = new StatsTrack(new String(node.data));
            updatedStat.setBytes(updatedStat.getBytes() + diff);
            node.data = updatedStat.toString().getBytes();
        }
        // now check if the bytes match the quota
        String quotaNode = Quotas.quotaPath(lastPrefix);
        node = nodes.get(quotaNode);
        if (node == null) {
            // should never be null but just to make
            // findbugs happy
            LOG.error("Missing quota node for bytes " + quotaNode);
            return;
        }
        StatsTrack thisStats = null;
        synchronized (node) {
            thisStats = new StatsTrack(new String(node.data));
        }
        if (thisStats.getBytes() > -1 && (thisStats.getBytes() < updatedStat.getBytes())) {
            LOG.warn("Quota exceeded: " + lastPrefix + " bytes=" + updatedStat.getBytes() + " limit=" + thisStats.getBytes());
        }
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     * 			  Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @throws NodeExistsException
     * @throws NoNodeException
     * @throws KeeperException
     */
    public void createNode(final String path, byte data[], List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time) throws NoNodeException, NodeExistsException {
    	createNode(path, data, acl, ephemeralOwner, parentCVersion, zxid, time, null);
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     * 			  Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @param outputStat
     * 			  A Stat object to store Stat output results into.
     * @throws NodeExistsException
     * @throws NoNodeException
     * @throws KeeperException
     */
    // 创造新节点
    public void createNode(final String path, byte data[], List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time, Stat outputStat)
            throws KeeperException.NoNodeException, KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        StatPersisted stat = new StatPersisted();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children.contains(childName)) {
                throw new KeeperException.NodeExistsException();
            }

            if (parentCVersion == -1) {                       // 如果 parentCVersion 为 -1
                parentCVersion = parent.stat.getCversion();   // 创建node的时候，parent.stat.cversion 竟然变了
                parentCVersion++;
            }
            parent.stat.setCversion(parentCVersion);          // 设置parent的cversion
            parent.stat.setPzxid(zxid);                       // 且将parent的pzxid设置成 zxid
            Long longval = aclCache.convertAcls(acl);         // 得到acl索引
            DataNode child = new DataNode(data, longval, stat);  // 创建子 DataNode

            parent.addChild(childName);
            nodes.put(path, child);
            EphemeralType ephemeralType = EphemeralType.get(ephemeralOwner);  // 根据ephemeralOwner得到ephemeralType
            if (ephemeralType == EphemeralType.CONTAINER) {        //如果是CONTAINER
                containers.add(path);
            } else if (ephemeralType == EphemeralType.TTL) {       // 如果是TTL
                ttls.add(path);
            } else if (ephemeralOwner != 0) {                            // 否则，加入到ephemerals
                HashSet<String> list = ephemerals.get(ephemeralOwner);   // 不是sessionId？ephemeralOwner是sessionId？或者两者没有重叠？
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized (list) {
                    list.add(path);
                }
            }
            if (outputStat != null) {
            	child.copyStat(outputStat);
            }
        }
        // now check if its one of the zookeeper node child
        if (parentName.startsWith(quotaZookeeper)) {     // 如果parentName是 /zookeeper/quota开头
            // now check if its the limit node
            if (Quotas.limitNode.equals(childName)) {    // 如果childName是 zookeeper_limits
                // this is the limit node
                // get the parent and add it to the trie                          // /zookeeper/quota + path + /zookeeper_limits or /zookeeper_stats
                pTrie.addPath(parentName.substring(quotaZookeeper.length()));     // 将path 添加到pTrie中
            }
            if (Quotas.statNode.equals(childName)) {                               // 如果childName是zookeeper_stats节点
                updateQuotaForPath(parentName.substring(quotaZookeeper.length())); // 将以path为根节点的子树的节点数以及数据大小放入nodes中
            }                                                                      // path在/zookeeper/quota节点下可能存在对应的统计数据
        }
        // also check to update the quotas for this node
        // 找到最长的还没有被删除的path
        String lastPrefix = getMaxPrefixWithQuota(path);
        if(lastPrefix != null) {
            // ok we have some match and need to update         quota相关
            updateCount(lastPrefix, 1);
            updateBytes(lastPrefix, data == null ? 0 : data.length);
        }
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);                                                // 触发节点创建watch
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName, Event.EventType.NodeChildrenChanged);   // 触发parentPath的NodeChildrenChanged事件
    }

    /**
     * remove the path from the datatree
     *
     * @param path
     *            the path to of the node to be deleted
     * @param zxid
     *            the current zxid
     * @throws KeeperException.NoNodeException
     */
    public void deleteNode(String path, long zxid) throws KeeperException.NoNodeException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException();
        }
        nodes.remove(path);        // 删除掉path对应的DataNode
        synchronized (node) {      // 删除对应的ACL权限数据
            aclCache.removeUsage(node.acl);
        }
        DataNode parent = nodes.get(parentName);   // 得到父节点
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            parent.removeChild(childName);    // 更新parent.children
            parent.stat.setPzxid(zxid);       // 设置parent.stat.pzxid
            long eowner = node.stat.getEphemeralOwner();
            EphemeralType ephemeralType = EphemeralType.get(eowner);
            if (ephemeralType == EphemeralType.CONTAINER) {    //如果删掉的node是CONTAINER
                containers.remove(path);
            } else if (ephemeralType == EphemeralType.TTL) {
                ttls.remove(path);
            } else if (eowner != 0) {                          // 否则 临时节点
                HashSet<String> nodes = ephemerals.get(eowner);
                if (nodes != null) {
                    synchronized (nodes) {
                        nodes.remove(path);
                    }
                }
            }
        }
        if (parentName.startsWith(procZookeeper) && Quotas.limitNode.equals(childName)) {  // quota limit
            // delete the node in the trie.
            // we need to update the trie as well
            pTrie.deletePath(parentName.substring(quotaZookeeper.length()));      // 从pTrie中删除
        }

        // also check to update the quotas for this node
        String lastPrefix = getMaxPrefixWithQuota(path);    // 限额配置
        if(lastPrefix != null) {
            // ok we have some match and need to update
            updateCount(lastPrefix, -1);
            int bytes = 0;
            synchronized (node) {
                bytes = (node.data == null ? 0 : -(node.data.length));
            }
            updateBytes(lastPrefix, bytes);
        }
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "dataWatches.triggerWatch " + path);
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "childWatches.triggerWatch " + parentName);
        }
        Set<Watcher> processed = dataWatches.triggerWatch(path, EventType.NodeDeleted);                      //触发path的NodeDeleted事件
        childWatches.triggerWatch(path, EventType.NodeDeleted, processed);                                   //触发 childWatches的NodeDeleted事件
        childWatches.triggerWatch("".equals(parentName) ? "/" : parentName, EventType.NodeChildrenChanged);  //触发parent节点的NodeChildrenChanged事件
    }

    public Stat setData(String path, byte data[], int version, long zxid, long time) throws KeeperException.NoNodeException {
        Stat s = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        byte lastdata[] = null;
        synchronized (n) {
            lastdata = n.data;
            n.data = data;               // 修改data
            n.stat.setMtime(time);       // 修改mtime
            n.stat.setMzxid(zxid);       // 修改mzxid
            n.stat.setVersion(version);  // 修改version
            n.copyStat(s);
        }
        // now update if the path is in a quota subtree.
        String lastPrefix = getMaxPrefixWithQuota(path);
        if(lastPrefix != null) {
          this.updateBytes(lastPrefix, (data == null ? 0 : data.length) - (lastdata == null ? 0 : lastdata.length));
        }
        dataWatches.triggerWatch(path, EventType.NodeDataChanged);        // 触发path的NodeDataChanged时间  不通知parent？
        return s;
    }

    /**
     * If there is a quota set, return the appropriate prefix for that quota
     * Else return null
     * @param path The ZK path to check for quota
     * @return Max quota prefix, or null if none
     */
    public String getMaxPrefixWithQuota(String path) {
        // do nothing for the root.
        // we are not keeping a quota on the zookeeper
        // root node for now.
        String lastPrefix = pTrie.findMaxPrefix(path);

        if (rootZookeeper.equals(lastPrefix) || "".equals(lastPrefix)) {
            return null;
        }
        else {
            return lastPrefix;
        }
    }

    // 获取数据
    public byte[] getData(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            return n.data;
        }
    }

    public Stat statNode(String path, Watcher watcher) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return stat;
        }
    }

    public List<String> getChildren(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            List<String> children=new ArrayList<String>(n.getChildren());

            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
            return children;
        }
    }

    public Stat setACL(String path, List<ACL> acl, int version) throws KeeperException.NoNodeException {
        Stat stat = new Stat();            //新建了一个stat
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            aclCache.removeUsage(n.acl);           // 将原来的acl从aclCache中删除掉
            n.stat.setAversion(version);           // 这是设置acl版本号？
            n.acl = aclCache.convertAcls(acl);     // 设置新的acl,并保存其index
            n.copyStat(stat);                      // 将n.stat的属性复制到新建的stat，不发布n.stat，而发布n.stat的一个复制
            return stat;
        }
    }

    // 将path对应DataNode的stat属性值赋值给stat
    // 并返回DataNode的acl列表
    public List<ACL> getACL(String path, Stat stat) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return new ArrayList<ACL>(aclCache.convertLong(n.acl));
        }
    }

    public List<ACL> getACL(DataNode node) {
        synchronized (node) {
            return aclCache.convertLong(node.acl);
        }
    }

    public int aclCacheSize() {
        return aclCache.size();
    }

    static public class ProcessTxnResult {
        public long clientId;
        public int cxid;
        public long zxid;
        public int err;
        public int type;
        public String path;
        public Stat stat;

        public List<ProcessTxnResult> multiResult;

        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof ProcessTxnResult) {
                ProcessTxnResult other = (ProcessTxnResult) o;
                return other.clientId == clientId && other.cxid == cxid;
            }
            return false;
        }

        /**
         * See equals() to find the rational for how this hashcode is generated.
         *
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return (int) ((clientId ^ cxid) % Integer.MAX_VALUE);
        }
    }

    public volatile long lastProcessedZxid = 0;

    public ProcessTxnResult processTxn(TxnHeader header, Record txn) {
        ProcessTxnResult rc = new ProcessTxnResult();

        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            rc.multiResult = null;
            switch (header.getType()) {
                case OpCode.create:
                    CreateTxn createTxn = (CreateTxn) txn;
                    rc.path = createTxn.getPath();
                    createNode(
                            createTxn.getPath(),
                            createTxn.getData(),
                            createTxn.getAcl(),
                            createTxn.getEphemeral() ? header.getClientId() : 0,   // 如果不是永久节点，则ephemeralOwner为clientId，否则为0
                            createTxn.getParentCVersion(),                                        // 0对应VOID，永久节点
                            header.getZxid(), header.getTime(), null);
                    break;
                case OpCode.create2:
                    CreateTxn create2Txn = (CreateTxn) txn;
                    rc.path = create2Txn.getPath();
                    Stat stat = new Stat();
                    createNode(
                            create2Txn.getPath(),
                            create2Txn.getData(),
                            create2Txn.getAcl(),
                            create2Txn.getEphemeral() ? header.getClientId() : 0,
                            create2Txn.getParentCVersion(),
                            header.getZxid(), header.getTime(), stat);
                    rc.stat = stat;
                    break;
                case OpCode.createTTL:
                    CreateTTLTxn createTtlTxn = (CreateTTLTxn) txn;
                    rc.path = createTtlTxn.getPath();
                    stat = new Stat();
                    createNode(
                            createTtlTxn.getPath(),
                            createTtlTxn.getData(),
                            createTtlTxn.getAcl(),
                            EphemeralType.TTL.toEphemeralOwner(createTtlTxn.getTtl()),
                            createTtlTxn.getParentCVersion(),
                            header.getZxid(), header.getTime(), stat);
                    rc.stat = stat;
                    break;
                case OpCode.createContainer:
                    CreateContainerTxn createContainerTxn = (CreateContainerTxn) txn;
                    rc.path = createContainerTxn.getPath();
                    stat = new Stat();
                    createNode(
                            createContainerTxn.getPath(),
                            createContainerTxn.getData(),
                            createContainerTxn.getAcl(),
                            EphemeralType.CONTAINER_EPHEMERAL_OWNER,
                            createContainerTxn.getParentCVersion(),
                            header.getZxid(), header.getTime(), stat);
                    rc.stat = stat;
                    break;
                case OpCode.delete:
                case OpCode.deleteContainer:
                    DeleteTxn deleteTxn = (DeleteTxn) txn;
                    rc.path = deleteTxn.getPath();
                    deleteNode(deleteTxn.getPath(), header.getZxid());
                    break;
                case OpCode.reconfig:
                case OpCode.setData:
                    SetDataTxn setDataTxn = (SetDataTxn) txn;
                    rc.path = setDataTxn.getPath();
                    rc.stat = setData(setDataTxn.getPath(), setDataTxn.getData(), setDataTxn.getVersion(), header.getZxid(), header.getTime());
                    break;
                case OpCode.setACL:
                    SetACLTxn setACLTxn = (SetACLTxn) txn;
                    rc.path = setACLTxn.getPath();
                    rc.stat = setACL(setACLTxn.getPath(), setACLTxn.getAcl(), setACLTxn.getVersion());
                    break;
                case OpCode.closeSession:
                    killSession(header.getClientId(), header.getZxid());
                    break;
                case OpCode.error:
                    ErrorTxn errTxn = (ErrorTxn) txn;
                    rc.err = errTxn.getErr();
                    break;
                case OpCode.check:
                    CheckVersionTxn checkTxn = (CheckVersionTxn) txn;
                    rc.path = checkTxn.getPath();
                    break;
                case OpCode.multi:                                              // 批量操作？ 应该是递归解决吧？
                    MultiTxn multiTxn = (MultiTxn) txn ;
                    List<Txn> txns = multiTxn.getTxns();
                    rc.multiResult = new ArrayList<ProcessTxnResult>();
                    boolean failed = false;                             // 当前指令集合中是否含有OpCode.error
                    for (Txn subtxn : txns) {                           // 如果含有 OpCode.error
                        if (subtxn.getType() == OpCode.error) {
                            failed = true;
                            break;
                        }
                    }

                    boolean post_failed = false;           // OpCode.error的指令是否已经出现了，包括自身
                    for (Txn subtxn : txns) {
                        ByteBuffer bb = ByteBuffer.wrap(subtxn.getData());     // 每次操作的数据
                        Record record = null;
                        switch (subtxn.getType()) {
                            case OpCode.create:
                                record = new CreateTxn();
                                break;
                            case OpCode.createTTL:
                                record = new CreateTTLTxn();
                                break;
                            case OpCode.createContainer:
                                record = new CreateContainerTxn();
                                break;
                            case OpCode.delete:
                            case OpCode.deleteContainer:
                                record = new DeleteTxn();
                                break;
                            case OpCode.setData:
                                record = new SetDataTxn();
                                break;
                            case OpCode.error:
                                record = new ErrorTxn();
                                post_failed = true;
                                break;
                            case OpCode.check:
                                record = new CheckVersionTxn();
                                break;
                            default:
                                throw new IOException("Invalid type of op: " + subtxn.getType());
                        }
                        assert(record != null);

                        ByteBufferInputStream.byteBuffer2Record(bb, record);

                        // 如果是failed 且 当前type不为 error
                        // 即当前指令 之前或者之后存在 OpCode.error的指令
                        if (failed && subtxn.getType() != OpCode.error){

                            //如果之前已经出现了OpCode.error的指令  RUNTIMEINCONSISTENCY
                            // 否则 OK
                            int ec = post_failed ? Code.RUNTIMEINCONSISTENCY.intValue() : Code.OK.intValue();

                            subtxn.setType(OpCode.error);
                            record = new ErrorTxn(ec);
                        }

                        if (failed) {
                            assert(subtxn.getType() == OpCode.error) ;
                        }

                        TxnHeader subHdr = new TxnHeader(header.getClientId(), header.getCxid(),
                                                         header.getZxid(), header.getTime(),
                                                         subtxn.getType());
                        ProcessTxnResult subRc = processTxn(subHdr, record);
                        rc.multiResult.add(subRc);

                        //如果处理结果的err不为0，且rc.err为0
                        //则设置rc.err为处理结果的err
                        if (subRc.err != 0 && rc.err == 0) {
                            rc.err = subRc.err ;
                        }
                    }
                    break;
            }
        } catch (KeeperException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed: " + header + ":" + txn, e);
            }
            rc.err = e.code().intValue();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed: " + header + ":" + txn, e);
            }
        }
        /*
         * A snapshot might be in progress while we are modifying the data           当我们修改 tree data时，一个snapshot可能正在 in progress？（持久化？）
         * tree. If we set lastProcessedZxid prior to making corresponding           如果我们在修改tree之前改变了lastProcessedZxid
         * change to the tree, then the zxid associated with the snapshot            那么，和snapshot文件关联的zxid就会超过其内容(表现为snapshot里面的zxid会小于当前zxid)
         * file will be ahead of its contents. Thus, while restoring from            那么，当从snapshot进行恢复操作时
         * the snapshot, the restore method will not apply the transaction           将不会应用zxid的transaction
         * for zxid associated with the snapshot file, since the restore
         * method assumes that transaction to be present in the snapshot.
         *
         * To avoid this, we first apply the transaction and then modify             为了避免this，我们先实施这个事务，然后修改 lastProcessedZxid
         * lastProcessedZxid.  During restore, we correctly handle the               恢复的时候，只会有一种情况
         * case where the snapshot contains data ahead of the zxid associated        即，zxid对应的snapshot的内容的实际zxid会超过其声明的zxid
         * with the file.
         */
        if (rc.zxid > lastProcessedZxid) {
            lastProcessedZxid = rc.zxid;
        }

        /*
         * Snapshots are taken lazily. It can happen that the child      当父节点被序列化后，子节点可能正在创建
         * znodes of a parent are created after the parent               (父节点和子节点应该是同一个zxid？当前snapshot对应的最大zxid应该不包含当前这个zxid？)
         * is serialized. Therefore, while replaying logs during restore, a   那么当从snapshot恢复的时候，由于parent节点之前已经被序列化过，当恢复了snapshot后，
         * create might fail because the node was already                     父节点已经被创建？
         * created.                                                          然后回放事务日志
         *                                                                   会发现父节点已经被创建了 则NODEEXISTS？
         * After seeing this failure, we should increment
         * the cversion of the parent znode since the parent was serialized
         * before its children.
         *
         * Note, such failures on DT should be seen only during
         * restore.
         */
        if (header.getType() == OpCode.create && rc.err == Code.NODEEXISTS.intValue()) {
            LOG.debug("Adjusting parent cversion for Txn: " + header.getType() + " path:" + rc.path + " err: " + rc.err);
            int lastSlash = rc.path.lastIndexOf('/');
            String parentName = rc.path.substring(0, lastSlash);
            CreateTxn cTxn = (CreateTxn)txn;
            try {
                setCversionPzxid(parentName, cTxn.getParentCVersion(), header.getZxid());
            } catch (KeeperException.NoNodeException e) {
                LOG.error("Failed to set parent cversion for: " + parentName, e);
                rc.err = e.code().intValue();
            }
        } else if (rc.err != Code.OK.intValue()) {
            LOG.debug("Ignoring processTxn failure hdr: " + header.getType() + " : error: " + rc.err);
        }
        return rc;
    }

    void killSession(long session, long zxid) {
        // the list is already removed from the ephemerals
        // so we do not have to worry about synchronizing on
        // the list. This is only called from FinalRequestProcessor
        // so there is no need for synchronization. The list is not
        // changed here. Only create and delete change the list which
        // are again called from FinalRequestProcessor in sequence.
        HashSet<String> list = ephemerals.remove(session);
        if (list != null) {
            for (String path : list) {
                try {
                    deleteNode(path, zxid);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Deleting ephemeral node " + path + " for session 0x" + Long.toHexString(session));
                    }
                } catch (NoNodeException e) {
                    LOG.warn("Ignoring NoNodeException for path " + path + " while removing ephemeral for dead session 0x" + Long.toHexString(session));
                }
            }
        }
    }

    /**
     * a encapsultaing class for return value
     */
    private static class Counts {
        long bytes;
        int count;
    }

    /**
     * this method gets the count of nodes and the bytes under a subtree
     *
     * @param path
     *            the path to be used
     * @param counts
     *            the int count
     */
    //得到 以path为根节点的子树的节点数以及数据长度
    private void getCounts(String path, Counts counts) {
        DataNode node = getNode(path);
        if (node == null) {
            return;
        }
        String[] children = null;
        int len = 0;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
            len = (node.data == null ? 0 : node.data.length);
        }
        // add itself
        counts.count += 1;
        counts.bytes += len;
        for (String child : children) {
            getCounts(path + "/" + child, counts);
        }
    }

    /**
     * update the quota for the given path
     *
     * @param path
     *            the path to be used
     */
    // 更新 统计信息
    // 节点个数 数据长度
    private void updateQuotaForPath(String path) {
        Counts c = new Counts();
        getCounts(path, c);
        StatsTrack strack = new StatsTrack();
        strack.setBytes(c.bytes);
        strack.setCount(c.count);
        String statPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        DataNode node = getNode(statPath);
        // it should exist
        if (node == null) {
            LOG.warn("Missing quota stat node " + statPath);
            return;
        }
        synchronized (node) {
            node.data = strack.toString().getBytes();
        }
    }

    /**
     * this method traverses the quota path and update the path trie and sets
     *
     * @param path
     */
    private void traverseNode(String path) {
        DataNode node = getNode(path);
        String children[] = null;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        if (children.length == 0) {
            // this node does not have a child
            // is the leaf node
            // check if its the leaf node
            String endString = "/" + Quotas.limitNode;
            if (path.endsWith(endString)) {
                // ok this is the limit node
                // get the real node and update
                // the count and the bytes
                String realPath = path.substring(Quotas.quotaZookeeper.length(), path.indexOf(endString));
                updateQuotaForPath(realPath);
                this.pTrie.addPath(realPath);
            }
            return;
        }
        for (String child : children) {
            traverseNode(path + "/" + child);
        }
    }

    /**
     * this method sets up the path trie and sets up stats for quota nodes
     */
    private void setupQuota() {
        String quotaPath = Quotas.quotaZookeeper;             //    /zookeeper/quota
        DataNode node = getNode(quotaPath);
        if (node == null) {
            return;
        }
        traverseNode(quotaPath);
    }

    /**
     * this method uses a stringbuilder to create a new path for children. This
     * is faster than string appends ( str1 + str2).
     *
     * @param oa
     *            OutputArchive to write to.
     * @param path
     *            a string builder.
     * @throws IOException
     * @throws InterruptedException
     */
    // 序列化节点 以path为根节点的子树
    void serializeNode(OutputArchive oa, StringBuilder path) throws IOException {
        String pathString = path.toString();
        DataNode node = getNode(pathString);
        if (node == null) {
            return;
        }
        String children[] = null;
        DataNode nodeCopy;
        synchronized (node) {
            StatPersisted statCopy = new StatPersisted();
            copyStatPersisted(node.stat, statCopy);
            //we do not need to make a copy of node.data because the contents
            //are never changed
            nodeCopy = new DataNode(node.data, node.acl, statCopy);
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        oa.writeString(pathString, "path");
        oa.writeRecord(nodeCopy, "node");
        path.append('/');
        int off = path.length();
        for (String child : children) {
            // since this is single buffer being resused
            // we need
            // to truncate the previous bytes of string.
            path.delete(off, Integer.MAX_VALUE);
            path.append(child);
            serializeNode(oa, path);
        }
    }

    //序列化
    public void serialize(OutputArchive oa, String tag) throws IOException {
        aclCache.serialize(oa);                     //序列化ACL
        serializeNode(oa, new StringBuilder(""));   //序列化树
        // / marks end of stream
        // we need to check if clear had been called in between the snapshot.
        if (root != null) {                        // root不为null，则写一个/
            oa.writeString("/", "path");
        }
    }

    public void deserialize(InputArchive ia, String tag) throws IOException {
        aclCache.deserialize(ia);
        nodes.clear();
        pTrie.clear();
        String path = ia.readString("path");
        while (!"/".equals(path)) {
            DataNode node = new DataNode();
            ia.readRecord(node, "node");
            nodes.put(path, node);
            synchronized (node) {
                aclCache.addUsage(node.acl);
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                root = node;
            } else {
                String parentPath = path.substring(0, lastSlash);
                DataNode parent = nodes.get(parentPath);
                if (parent == null) {
                    throw new IOException("Invalid Datatree, unable to find " + "parent " + parentPath + " of path " + path);
                }
                parent.addChild(path.substring(lastSlash + 1));
                long eowner = node.stat.getEphemeralOwner();
                EphemeralType ephemeralType = EphemeralType.get(eowner);
                if (ephemeralType == EphemeralType.CONTAINER) {
                    containers.add(path);
                } else if (ephemeralType == EphemeralType.TTL) {
                    ttls.add(path);
                } else if (eowner != 0) {
                    HashSet<String> list = ephemerals.get(eowner);
                    if (list == null) {
                        list = new HashSet<String>();
                        ephemerals.put(eowner, list);
                    }
                    list.add(path);
                }
            }
            path = ia.readString("path");
        }
        nodes.put("/", root);
        // we are done with deserializing the
        // the datatree
        // update the quotas - create path trie
        // and also update the stat nodes
        setupQuota();

        aclCache.purgeUnused();
    }

    /**
     * Summary of the watches on the datatree.
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatchesSummary(PrintWriter pwriter) {
        pwriter.print(dataWatches.toString());
    }

    /**
     * Write a text dump of all the watches on the datatree.
     * Warning, this is expensive, use sparingly!
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        dataWatches.dumpWatches(pwriter, byPath);
    }

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    public synchronized WatchesReport getWatches() {
        return dataWatches.getWatches();
    }

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    public synchronized WatchesPathReport getWatchesByPath() {
        return dataWatches.getWatchesByPath();
    }

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    public synchronized WatchesSummary getWatchesSummary() {
        return dataWatches.getWatchesSummary();
    }

    /**
     * Write a text dump of all the ephemerals in the datatree.
     * @param pwriter the output to write to
     */
    public void dumpEphemerals(PrintWriter pwriter) {
        pwriter.println("Sessions with Ephemerals (" + ephemerals.keySet().size() + "):");
        for (Entry<Long, HashSet<String>> entry : ephemerals.entrySet()) {
            pwriter.print("0x" + Long.toHexString(entry.getKey()));
            pwriter.println(":");
            HashSet<String> tmp = entry.getValue();
            if (tmp != null) {
                synchronized (tmp) {
                    for (String path : tmp) {
                        pwriter.println("\t" + path);
                    }
                }
            }
        }
    }

    /**
     * Returns a mapping of session ID to ephemeral znodes.
     *
     * @return map of session ID to sets of ephemeral znodes
     */
    public Map<Long, Set<String>> getEphemerals() {
        HashMap<Long, Set<String>> ephemeralsCopy = new HashMap<Long, Set<String>>();
        for (Entry<Long, HashSet<String>> e : ephemerals.entrySet()) {
            synchronized (e.getValue()) {
                ephemeralsCopy.put(e.getKey(), new HashSet<String>(e.getValue()));
            }
        }
        return ephemeralsCopy;
    }

    public void removeCnxn(Watcher watcher) {
        dataWatches.removeWatcher(watcher);
        childWatches.removeWatcher(watcher);
    }

    public void setWatches(long relativeZxid, List<String> dataWatches, List<String> existWatches, List<String> childWatches, Watcher watcher) {

        for (String path : dataWatches) {
            DataNode node = getNode(path);
            WatchedEvent e = null;
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getMzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }

        for (String path : existWatches) {
            DataNode node = getNode(path);
            if (node != null) {
                watcher.process(new WatchedEvent(EventType.NodeCreated, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }

        for (String path : childWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getPzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, path));
            } else {
                this.childWatches.addWatch(path, watcher);
            }
        }
    }

     /**
      * This method sets the Cversion and Pzxid for the specified node to the
      * values passed as arguments. The values are modified only if newCversion
      * is greater than the current Cversion. A NoNodeException is thrown if
      * a znode for the specified path is not found.
      *
      * @param path
      *     Full path to the znode whose Cversion needs to be modified.
      *     A "/" at the end of the path is ignored.
      * @param newCversion
      *     Value to be assigned to Cversion
      * @param zxid
      *     Value to be assigned to Pzxid
      * @throws KeeperException.NoNodeException
      *     If znode not found.
      **/
    public void setCversionPzxid(String path, int newCversion, long zxid) throws KeeperException.NoNodeException {
        if (path.endsWith("/")) {
           path = path.substring(0, path.length() - 1);
        }
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException(path);
        }
        synchronized (node) {
            if(newCversion == -1) {
                newCversion = node.stat.getCversion() + 1;
            }
            if (newCversion > node.stat.getCversion()) {
                node.stat.setCversion(newCversion);
                node.stat.setPzxid(zxid);
            }
        }
    }

    public boolean containsWatcher(String path, WatcherType type, Watcher watcher) {
        boolean containsWatcher = false;
        switch (type) {
        case Children:
            containsWatcher = this.childWatches.containsWatcher(path, watcher);
            break;
        case Data:
            containsWatcher = this.dataWatches.containsWatcher(path, watcher);
            break;
        case Any:
            if (this.childWatches.containsWatcher(path, watcher)) {
                containsWatcher = true;
            }
            if (this.dataWatches.containsWatcher(path, watcher)) {
                containsWatcher = true;
            }
            break;
        }
        return containsWatcher;
    }

    public boolean removeWatch(String path, WatcherType type, Watcher watcher) {
        boolean removed = false;
        switch (type) {
        case Children:
            removed = this.childWatches.removeWatcher(path, watcher);
            break;
        case Data:
            removed = this.dataWatches.removeWatcher(path, watcher);
            break;
        case Any:
            if (this.childWatches.removeWatcher(path, watcher)) {
                removed = true;
            }
            if (this.dataWatches.removeWatcher(path, watcher)) {
                removed = true;
            }
            break;
        }
        return removed;
    }

    // visible for testing
    public ReferenceCountedACLCache getReferenceCountedAclCache() {
        return aclCache;
    }
}
