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
package org.apache.hadoop.hdfs.net;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

/**
 * The HDFS specific network topology class. The main purpose of doing this
 * subclassing is to add storage-type-aware chooseRandom method. All the
 * remaining parts should be the same.
 *
 * Currently a placeholder to test storage type info.
 * TODO : add "chooseRandom with storageType info" function.
 */
public class DFSNetworkTopology extends NetworkTopology {

  private static final Random RANDOM = new Random();

  public static DFSNetworkTopology getInstance(Configuration conf) {
    DFSNetworkTopology nt = new DFSNetworkTopology();
    return (DFSNetworkTopology)nt.init(DFSTopologyNodeImpl.FACTORY);
  }

  /**
   * Randomly choose one node from <i>scope</i>, with specified storage type.
   *
   * If scope starts with ~, choose one from the all nodes except for the
   * ones in <i>scope</i>; otherwise, choose one from <i>scope</i>.
   * If excludedNodes is given, choose a node that's not in excludedNodes.
   *
   * @param scope range of nodes from which a node will be chosen
   * @param excludedNodes nodes to be excluded from
   * @return the chosen node
   */
  public Node chooseRandomWithStorageType(final String scope,
      final Collection<Node> excludedNodes, StorageType type) {
    netlock.readLock().lock();
    try {
      if (scope.startsWith("~")) {
        return chooseRandomWithStorageType(
            NodeBase.ROOT, scope.substring(1), excludedNodes, type);
      } else {
        return chooseRandomWithStorageType(
            scope, null, excludedNodes, type);
      }
    } finally {
      netlock.readLock().unlock();
    }
  }

  /**
   * Choose a random node based on given scope, excludedScope and excludedNodes
   * set. Although in general the topology has at most three layers, this class
   * will not impose such assumption.
   *
   * At high level, the idea is like this, say:
   *
   * R has two children A and B, and storage type is X, say:
   * A has X = 6 (rooted at A there are 6 datanodes with X) and B has X = 8.
   *
   * Then R will generate a random int between 1~14, if it's <= 6, recursively
   * call into A, otherwise B. This will maintain a uniformed randomness of
   * choosing datanodes.
   *
   * The tricky part is how to handle excludes.
   *
   * For excludedNodes, since this set is small: currently the main reason of
   * being an excluded node is because it already has a replica. So randomly
   * picking up this node again should be rare. Thus we only check that, if the
   * chosen node is excluded, we do chooseRandom again.
   *
   * For excludedScope, we locate the root of the excluded scope. Subtracting
   * all it's ancestors' storage counters accordingly, this way the excluded
   * root is out of the picture.
   *
   * TODO : this function has duplicate code as NetworkTopology, need to
   * refactor in the future.
   *
   * @param scope
   * @param excludedScope
   * @param excludedNodes
   * @return
   */
  @VisibleForTesting
  Node chooseRandomWithStorageType(final String scope,
      String excludedScope, final Collection<Node> excludedNodes,
      StorageType type) {
    if (excludedScope != null) {
      if (scope.startsWith(excludedScope)) {
        return null;
      }
      if (!excludedScope.startsWith(scope)) {
        excludedScope = null;
      }
    }
    Node node = getNode(scope);
    if (node == null) {
      LOG.debug("Invalid scope {}, non-existing node", scope);
      return null;
    }
    if (!(node instanceof DFSTopologyNodeImpl)) {
      // a node is either DFSTopologyNodeImpl, or a DatanodeDescriptor
      return ((DatanodeDescriptor)node).hasStorageType(type) ? node : null;
    }
    DFSTopologyNodeImpl root = (DFSTopologyNodeImpl)node;
    Node excludeRoot = excludedScope == null ? null : getNode(excludedScope);

    // check to see if there are nodes satisfying the condition at all
    int availableCount = root.getSubtreeStorageCount(type);
    if (excludeRoot != null && root.isAncestor(excludeRoot)) {
      if (excludeRoot instanceof DFSTopologyNodeImpl) {
        availableCount -= ((DFSTopologyNodeImpl)excludeRoot)
            .getSubtreeStorageCount(type);
      } else {
        availableCount -= ((DatanodeDescriptor)excludeRoot)
            .hasStorageType(type) ? 1 : 0;
      }
    }
    if (excludedNodes != null) {
      for (Node excludedNode : excludedNodes) {
        // all excluded nodes should be DatanodeDescriptor
        Preconditions.checkArgument(excludedNode instanceof DatanodeDescriptor);
        availableCount -= ((DatanodeDescriptor) excludedNode)
            .hasStorageType(type) ? 1 : 0;
      }
    }
    if (availableCount <= 0) {
      // should never be <0 in general, adding <0 check for safety purpose
      return null;
    }
    // to this point, it is guaranteed that there is at least one node
    // that satisfies the requirement, keep trying until we found one.
    Node chosen;
    do {
      chosen = chooseRandomWithStorageTypeAndExcludeRoot(root, excludeRoot,
          type);
      if (excludedNodes == null || !excludedNodes.contains(chosen)) {
        break;
      } else {
        LOG.debug("Node {} is excluded, continuing.", chosen);
      }
    } while (true);
    LOG.debug("chooseRandom returning {}", chosen);
    return chosen;
  }

  /**
   * Choose a random node that has the required storage type, under the given
   * root, with an excluded subtree root (could also just be a leaf node).
   *
   * Note that excludedNode is checked after a random node, so it is not being
   * handled here.
   *
   * @param root the root node where we start searching for a datanode
   * @param excludeRoot the root of the subtree what should be excluded
   * @param type the expected storage type
   * @return a random datanode, with the storage type, and is not in excluded
   * scope
   */
  private Node chooseRandomWithStorageTypeAndExcludeRoot(
      DFSTopologyNodeImpl root, Node excludeRoot, StorageType type) {
    Node chosenNode;
    if (root.isRack()) {
      // children are datanode descriptor
      ArrayList<Node> candidates = new ArrayList<>();
      for (Node node : root.getChildren()) {
        if (node.equals(excludeRoot)) {
          continue;
        }
        DatanodeDescriptor dnDescriptor = (DatanodeDescriptor)node;
        if (dnDescriptor.hasStorageType(type)) {
          candidates.add(node);
        }
      }
      if (candidates.size() == 0) {
        return null;
      }
      // to this point, all nodes in candidates are valid choices, and they are
      // all datanodes, pick a random one.
      chosenNode = candidates.get(RANDOM.nextInt(candidates.size()));
    } else {
      // the children are inner nodes
      ArrayList<DFSTopologyNodeImpl> candidates =
          getEligibleChildren(root, excludeRoot, type);
      if (candidates.size() == 0) {
        return null;
      }
      // again, all children are also inner nodes, we can do this cast.
      // to maintain uniformality, the search needs to be based on the counts
      // of valid datanodes. Below is a random weighted choose.
      int totalCounts = 0;
      int[] countArray = new int[candidates.size()];
      for (int i = 0; i < candidates.size(); i++) {
        DFSTopologyNodeImpl innerNode = candidates.get(i);
        int subTreeCount = innerNode.getSubtreeStorageCount(type);
        totalCounts += subTreeCount;
        countArray[i] = subTreeCount;
      }
      // generate a random val between [1, totalCounts]
      int randomCounts = RANDOM.nextInt(totalCounts) + 1;
      int idxChosen = 0;
      // searching for the idxChosen can potentially be done with binary
      // search, but does not seem to worth it here.
      for (int i = 0; i < countArray.length; i++) {
        if (randomCounts <= countArray[i]) {
          idxChosen = i;
          break;
        }
        randomCounts -= countArray[i];
      }
      DFSTopologyNodeImpl nextRoot = candidates.get(idxChosen);
      chosenNode = chooseRandomWithStorageTypeAndExcludeRoot(
          nextRoot, excludeRoot, type);
    }
    return chosenNode;
  }

  /**
   * Given root, excluded root and storage type. Find all the children of the
   * root, that has the storage type available. One check is that if the
   * excluded root is under a children, this children must subtract the storage
   * count of the excluded root.
   * @param root the subtree root we check.
   * @param excludeRoot the root of the subtree that should be excluded.
   * @param type the storage type we look for.
   * @return a list of possible nodes, each of them is eligible as the next
   * level root we search.
   */
  private ArrayList<DFSTopologyNodeImpl> getEligibleChildren(
      DFSTopologyNodeImpl root, Node excludeRoot, StorageType type) {
    ArrayList<DFSTopologyNodeImpl> candidates = new ArrayList<>();
    int excludeCount = 0;
    if (excludeRoot != null && root.isAncestor(excludeRoot)) {
      // the subtree to be excluded is under the given root,
      // find out the number of nodes to be excluded.
      if (excludeRoot instanceof DFSTopologyNodeImpl) {
        // if excludedRoot is an inner node, get the counts of all nodes on
        // this subtree of that storage type.
        excludeCount = ((DFSTopologyNodeImpl) excludeRoot)
            .getSubtreeStorageCount(type);
      } else {
        // if excludedRoot is a datanode, simply ignore this one node
        if (((DatanodeDescriptor) excludeRoot).hasStorageType(type)) {
          excludeCount = 1;
        }
      }
    }
    // have calculated the number of storage counts to be excluded.
    // walk through all children to check eligibility.
    for (Node node : root.getChildren()) {
      DFSTopologyNodeImpl dfsNode = (DFSTopologyNodeImpl) node;
      int storageCount = dfsNode.getSubtreeStorageCount(type);
      if (excludeRoot != null && excludeCount != 0 &&
          (dfsNode.isAncestor(excludeRoot) || dfsNode.equals(excludeRoot))) {
        storageCount -= excludeCount;
      }
      if (storageCount > 0) {
        candidates.add(dfsNode);
      }
    }
    return candidates;
  }
}
