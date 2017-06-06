/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package com.tencent.angel.ml.GBDT;


import com.tencent.angel.ml.GBDT.udf.GBDTGradHistGetRowFunc;
import com.tencent.angel.ml.RegTree.*;
import com.tencent.angel.ml.conf.MLConf;
import com.tencent.angel.ml.math.vector.*;
import com.tencent.angel.ml.matrix.psf.get.single.*;
import com.tencent.angel.ml.metric.LogisErrorMetric;
import com.tencent.angel.ml.metric.Metric;
import com.tencent.angel.ml.model.PSModel;
import com.tencent.angel.ml.objective.Loss;
import com.tencent.angel.ml.objective.LossHelper;
import com.tencent.angel.ml.objective.ObjFunc;
import com.tencent.angel.ml.objective.RegLossObj;
import com.tencent.angel.ml.param.GBDTTrainParam;
import com.tencent.angel.ml.tree.SplitEntry;
import com.tencent.angel.ml.tree.TAvgDisSplit;
import com.tencent.angel.ml.tree.TNode;
import com.tencent.angel.ml.utils.MathUtils;
import com.tencent.angel.worker.task.TaskContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GBDTController {

  private static final Log LOG = LogFactory.getLog(GBDTController.class);

  public TaskContext taskContext;
  public GBDTModel model;
  public GBDTTrainParam param;
  public DataMeta dataMeta;
  public DataMeta validDataMeta;
  public DistributedRegTree[] forest;
  public int phase;
  public int clock;
  public int currentTree;
  public int currentDepth;
  public int maxNodeNum;

  // loss function
  public ObjFunc objfunc;
  // gradient and hessian
  public List<GradPair> gradPairs = new ArrayList<>();

  public float sketches[]; // size: featureNum * splitNum
  public int[] fset; // sampled features in the current tree
  public int[] activeNode; // active tree node, 1:active, 0:inactive
  public int[] activeNodeStat; // 2: finished, 1:running, 0:idle, -1:failed
  public int[] instancePos; // map tree node to instance, each item is instance id
  public int[] nodePosStart; // node's start index in instancePos, size: maxNodeNum
  public int[] nodePosEnd; // node's end index in instancePos, instances in [start, end] belong to a
                           // tree node

  private ExecutorService threadPool;

  public GBDTController(TaskContext taskContext, GBDTTrainParam param,
                        DataMeta dataMeta, DataMeta validData, GBDTModel model) {
    this.taskContext = taskContext;
    this.param = param;
    this.dataMeta = dataMeta;
    this.validDataMeta = validData;
    this.model = model;
  }

  public void init() throws Exception {
    this.forest = new DistributedRegTree[this.param.treeNum];
    // initialize the phase
    this.phase = GBDTPhase.CREATE_SKETCH;
    this.clock = 0;
    // current tree and depth
    this.currentTree = 0; // tree starts from 0
    this.currentDepth = 1; // depth starts from 1
    // create loss function
    LossHelper loss = new Loss.LogisticClassification();
    objfunc = new RegLossObj(loss);
    this.sketches = new float[this.param.numFeature * this.param.numSplit];
    this.maxNodeNum = MathUtils.pow(2, this.param.maxDepth) - 1;
    this.activeNode = new int[maxNodeNum];
    this.activeNodeStat = new int[maxNodeNum];
    this.instancePos = new int[dataMeta.numRow];
    for (int i = 0; i < this.dataMeta.instances.size(); i++) {
      this.instancePos[i] = i;
    }
    this.nodePosStart = new int[maxNodeNum];
    this.nodePosEnd = new int[maxNodeNum];
    this.nodePosStart[0] = 0;
    this.nodePosEnd[0] = instancePos.length - 1;
    this.threadPool = Executors.newFixedThreadPool(this.param.maxThreadNum);

  }

  private void clockAllMatrix(Set<String> needFlushMatrices, boolean wait) throws Exception {
    LOG.info("Clock and flush matrix: " + needFlushMatrices);
    long startTime = System.currentTimeMillis();

    List<Future> clockFutures = new ArrayList<Future>();
    for (Map.Entry<String, PSModel<?>> entry : model.getPsModels().entrySet()) {
      if (needFlushMatrices.contains(entry.getKey())) {
        //LOG.info("clock and flush matrix: " + entry.getKey());
        clockFutures.add(entry.getValue().clock(true));
      } else {
        //LOG.info("clock matrix: " + entry.getKey());
        clockFutures.add(entry.getValue().clock(false));
      }
    }

    if (wait) {
      int size = clockFutures.size();
      for (int i = 0; i < size; i++) {
        clockFutures.get(i).get();
      }
    }

    LOG.info(String.format("clock all matrices cost %d ms",
            System.currentTimeMillis() - startTime));
  }

  // calculate grad info of each instance
  private void calGradPairs() {
    LOG.info("------Calculate grad pairs------");
    gradPairs.clear();
    gradPairs.addAll(objfunc.getGradient(this.dataMeta.preds, this.dataMeta, 0));
    LOG.info(String.format(
        "The first instance[%d]: label[%f], pred[%f], gradient[%f], hessien[%f]", 0,
        this.dataMeta.labels[0], this.dataMeta.preds[0], gradPairs.get(0).getGrad(),
        gradPairs.get(0).getHess()));
  }

  // create data sketch, push candidate split value to PS
  public void createSketch() throws Exception {
    PSModel sketch = model.getPsModels().get(this.param.sketchName);
    if (taskContext.getTaskIndex() == 0) {
      LOG.info("------Create sketch------");
      long startTime = System.currentTimeMillis();
      DenseDoubleVector sketchVec =
          new DenseDoubleVector(this.param.numFeature * this.param.numSplit);
      // 1. calculate candidate split value
      float[][] splits = TAvgDisSplit.getSplitValue(this.dataMeta, this.param.numSplit);
      // float[][] splits = TYahooSketchSplit.getSplitValue(this.dataMeta, this.param.numSplit);
      if (splits.length == this.param.numFeature && splits[0].length == this.param.numSplit) {
        for (int fid = 0; fid < splits.length; fid++) {
          // LOG.info(String.format("Candidate splits of fid[%d]: %s", fid,
          // Arrays.toString(splits[fid])));
          for (int j = 0; j < splits[fid].length; j++) {
            sketchVec.set(fid * this.param.numSplit + j, splits[fid][j]);
          }
        }
      } else {
        LOG.error("Incompatible sketches size.");
      }
      // LOG.info(String.format("Local sketch size[%d]: %s",
      // sketchVec.getDimension(), Arrays.toString(sketchVec.getValues())));
      // 2. push local sketch to PS
      sketch.increment(0, sketchVec);
      // sketchClient.activateOpLog(new String[] {this.param.sketchName});
      // 3. set phase to GET_SKETCH
      this.phase = GBDTPhase.GET_SKETCH;
      LOG.info(String.format("Create sketch cost: %d ms", System.currentTimeMillis() - startTime));
    }

    Set<String> needFlushMatrixSet = new HashSet<String>(1);
    needFlushMatrixSet.add(this.param.sketchName);
    clockAllMatrix(needFlushMatrixSet, true);
  }

  // pull the global sketch from PS, only called once by each worker
  public void getSketch() throws Exception {
    PSModel sketch = model.getPsModels().get(this.param.sketchName);
    LOG.info("------Get sketch from PS------");
    long startTime = System.currentTimeMillis();
    TDoubleVector sketchVector = (TDoubleVector) sketch.getRow(0);
    // LOG.info(String.format("Sketch vector: %s", Arrays.toString(sketchVector.getValues())));
    for (int i = 0; i < sketchVector.getDimension(); i++) {
      this.sketches[i] = (float) sketchVector.get(i);
    }
    // this.sketches = Floats.toArray(Doubles.asList(sketchVector.getValues()));
    this.phase = GBDTPhase.NEW_TREE;
    LOG.info(String.format("Get sketch cost: %d ms", System.currentTimeMillis() - startTime));
  }

  // sample feature
  public void sampleFeature() throws Exception {
    LOG.info("------Sample feature------");
    PSModel featSample = model.getPsModels().get(this.param.sampledFeaturesName);
    Set<String> needFlushMatrixSet = new HashSet<String>(1);

    if (this.param.colSample < 1 && taskContext.getTaskIndex() == 0) {
      long startTime = System.currentTimeMillis();
      // push sampled feature set to the current tree
      if (this.param.colSample < 1) {
        int[] fset = this.dataMeta.featureMeta.sampleCol(this.param.colSample);
        DenseIntVector sampleFeatureVector = new DenseIntVector(fset.length, fset);
        featSample.increment(currentTree, sampleFeatureVector);
        // this.taskContext.activateOpLog(new String[] {this.param.sampledFeaturesName});
        needFlushMatrixSet.add(this.param.sampledFeaturesName);
      }
      LOG.info(String.format("Sample feature cost: %d ms", System.currentTimeMillis() - startTime));
    }

    clockAllMatrix(needFlushMatrixSet, true);
  }

  // create new tree
  // pull sampled features, initialize tree nodes, reset active nodes, reset instance position,
  // calculate gradient
  public void createNewTree() throws Exception {
    LOG.info("------Create new tree------");
    long startTime = System.currentTimeMillis();
    // 1. create new tree, initialize tree nodes and node stats
    DistributedRegTree tree = new DistributedRegTree(this.param);
    tree.initTreeNodes();
    this.currentDepth = 1;
    this.forest[this.currentTree] = tree;
    // 2. initialize feature set, if sampled, get from PS, otherwise use all the features
    if (this.param.colSample < 1) {
      // 2.1. pull the sampled features of the current tree
      PSModel featSample = model.getPsModels().get(this.param.sampledFeaturesName);
      DenseIntVector sampleFeatureVector =
          (DenseIntVector) featSample.getRow(this.currentTree);
      this.fset = sampleFeatureVector.getValues();
      this.forest[this.currentTree].fset = sampleFeatureVector.getValues();
    } else {
      // 2.2. if use all the featues, only called one
      if (null == this.fset) {
        this.fset = new int[this.dataMeta.featureMeta.numFeature];
        for (int fid = 0; fid < this.fset.length; fid++) {
          this.fset[fid] = fid;
        }
      }
    }
    // 3. reset active tree nodes, set all tree nodes to inactive, set thread status to idle
    for (int nid = 0; nid < this.maxNodeNum; nid++) {
      resetActiveTNodes(nid);
    }

    // 4. set root node to active
    addActiveNode(0);

    // 5. reset instance position, set the root node's span
    this.nodePosStart[0] = 0;
    this.nodePosEnd[0] = this.instancePos.length - 1;
    for (int nid = 1; nid < this.maxNodeNum; nid++) {
      this.nodePosStart[nid] = -1;
      this.nodePosEnd[nid] = -1;
    }

    // 6. calculate gradient
    calGradPairs();

    // 7. set phase to run active
    this.phase = GBDTPhase.RUN_ACTIVE;
    LOG.info(String.format("Create new tree cost: %d ms", System.currentTimeMillis() - startTime));
  }

  public void runActiveNode() throws Exception {
    LOG.info("------Run active node------");
    long startTime = System.currentTimeMillis();
    Set<String> needFlushMatrixSet = new HashSet<String>();

    // 1. start threads of active tree nodes
    for (int nid = 0; nid < this.maxNodeNum; nid++) {
      if (this.activeNode[nid] == 1) {
        String histParaName = this.param.gradHistNamePrefix + nid;

        // 1.1. start threads for active nodes to generate histogram
        PSModel histMat = model.getPsModels().get(histParaName);
        LOG.info(String.format("Run active node[%d]", nid));
        ActiveTNodeRunner runner = new ActiveTNodeRunner(this, nid, histMat);
        this.threadPool.submit(runner);
        // 1.2. set thread status to running
        this.activeNodeStat[nid] = 1;
        // 1.3. set the oplog to active
        // activeOpLogSet.add(histParaName);
        needFlushMatrixSet.add(histParaName);
      }
    }
    // 2. check thread stats, if all threads finish, return
    boolean hasRunning = true;
    while (hasRunning) {
      hasRunning = false;
      for (int nid = 0; nid < this.maxNodeNum; nid++) {
        int stat = this.activeNodeStat[nid];
        if (stat == 1) {
          hasRunning = true;
          break;
        } else if (stat == -1) {
          LOG.error(String.format("Histogram build thread of tree node[%d] failed", nid));
        }
      }
      if (hasRunning) {
        LOG.debug("current has running thread");
      }
    }
    // this.taskContext.activateOpLog(activeOpLogSet);
    this.phase = GBDTPhase.FIND_SPLIT;
    LOG.info(String.format("Run active node cost: %d ms", System.currentTimeMillis() - startTime));

    // clock
    clockAllMatrix(needFlushMatrixSet, true);
  }

  // find split
  public void findSplit() throws Exception {
    LOG.info("------Find split------");
    long startTime = System.currentTimeMillis();
    // 1. find responsible tree node, using RR scheme
    List<Integer> responsibleTNode = new ArrayList<>();
    int activeTNodeNum = 0;
    for (int nid = 0; nid < this.activeNode.length; nid++) {
      int isActive = this.activeNode[nid];
      if (isActive == 1) {
        if (this.taskContext.getTaskIndex() == activeTNodeNum) {
          responsibleTNode.add(nid);
        }
        if (++activeTNodeNum >= taskContext.getTotalTaskNum()) {
          activeTNodeNum = 0;
        }
      }
    }
    // // 2. find responsible tree node, using RR scheme
    // int totalTaskNum = MLContext.get().getTotalTaskNum();
    // int taskId = this.taskContext.getTaskId();
    // int fullRound = activeTNodeNum / totalTaskNum; // # of full RR round
    // int lastRound = (activeTNodeNum % totalTaskNum > taskId) ? 1: 0; // # of last RR round
    // int responsibleNum = fullRound + lastRound;
    // int[] tNodeId = new int[responsibleNum];
    // for (int i = 0; i < responsibleNum; i++) { // loop over RR round
    // tNodeId[i] = i * totalTaskNum + taskId;
    // }
    int[] tNodeId = MathUtils.intList2Arr(responsibleTNode);
    LOG.info(String.format("Task[%d] responsible tree node: %s", this.taskContext.getTaskId().getIndex(),
        responsibleTNode.toString()));

    // 2. pull gradient histogram
    int[] updatedIndices = new int[tNodeId.length]; // the updated indices of the parameter on PS
    int[] updatedSplitFid = new int[tNodeId.length]; // the updated split features
    double[] updatedSplitFvalue = new double[tNodeId.length]; // the updated split value
    double[] updatedSplitGain = new double[tNodeId.length]; // the updated split gain

    boolean isServerSplit =
        taskContext.getConf().getBoolean(MLConf.ML_GBDT_SERVER_SPLIT(),
                MLConf.DEFAULT_ML_GBDT_SERVER_SPLIT());

    for (int i = 0; i < tNodeId.length; i++) {
      int nid = tNodeId[i];
      LOG.info(String.format("Task[%d] find best split of tree node: %d",
              this.taskContext.getTaskIndex(), nid));
      // 2.1. get the name of this node's gradient histogram on PS
      String gradHistName = this.param.gradHistNamePrefix + nid;
      // 2.2. create row index of histogram
      // RowIndex rowsIdx = new RowIndex(this.clock, this.taskContext.getMatrixId(gradHistName));
      // for (int j = 0; j < this.fset.length; j++) {
      // rowsIdx.addRowId(j);
      // }
      // 2.3. pull the histogram matrix, with multi-rows interface
      // GetRowsResult histograms = this.taskContext.getRows(gradHistName, rowsIdx);
      // 2.2. pull the histogram
      long pullStartTime = System.currentTimeMillis();
      PSModel histMat = model.getPsModels().get(gradHistName);

      TDoubleVector histogram = null;
      if (isServerSplit) {
        int matrixId = histMat.getMatrixId();
        GBDTGradHistGetRowFunc func = new GBDTGradHistGetRowFunc(new GetRowParam(matrixId, 0));
        histogram = (TDoubleVector) ((GetRowResult) histMat.get(func)).getRow();
      } else {
        LOG.info("Get grad histogram without server split mode.");
        histogram = (TDoubleVector) histMat.getRow(0);
      }

      LOG.info(String.format("Pull histogram from PS cost %d ms", System.currentTimeMillis()
          - pullStartTime));
      DistributedHistHelper histHelper = new DistributedHistHelper(this, nid);
      // find best split result of this tree node
      if (this.param.isServerSplit) {
        // 2.4. using server split, each partition of the histogram contains its best split result
        // find the best split from all partitions
        SplitEntry splitEntry = histHelper.findBestFromServerSplit(histogram);
        LOG.info(String.format("Best split of node[%d]: feature[%d], value[%f], losschg[%f]", nid,
            splitEntry.getFid(), splitEntry.getFvalue(), splitEntry.getLossChg()));
        // 2.5. the updated split result (tree node/feature/value/gain) on PS,
        updatedIndices[i] = nid;
        updatedSplitFid[i] = splitEntry.fid;
        updatedSplitFvalue[i] = splitEntry.fvalue;
        updatedSplitGain[i] = splitEntry.lossChg;
      } else {
        // 2.4. otherwise, the returned histogram contains the gradient info
        SplitEntry splitEntry = histHelper.findBestSplit(histogram);
        LOG.info(String.format("Best split of node[%d]: feature[%d], value[%f], losschg[%f]", nid,
            splitEntry.getFid(), splitEntry.getFvalue(), splitEntry.getLossChg()));
        // 2.5. the updated split result (tree node/feature/value/gain) on PS,
        updatedIndices[i] = nid;
        updatedSplitFid[i] = splitEntry.fid;
        updatedSplitFvalue[i] = splitEntry.fvalue;
        updatedSplitGain[i] = splitEntry.lossChg;
      }
      // 2.6. reset this tree node's gradient histogram to 0
      histMat.zero();
    }
    // 3. push split feature to PS
    DenseIntVector splitFeatureVector = new DenseIntVector(this.activeNode.length);
    // 4. push split value to PS
    DenseDoubleVector splitValueVector = new DenseDoubleVector(this.activeNode.length);
    // 5. push split gain to PS
    DenseDoubleVector splitGainVector = new DenseDoubleVector(this.activeNode.length);

    for (int i = 0; i < updatedIndices.length; i++) {
      splitFeatureVector.set(updatedIndices[i], updatedSplitFid[i]);
      splitValueVector.set(updatedIndices[i], updatedSplitFvalue[i]);
      splitGainVector.set(updatedIndices[i], updatedSplitGain[i]);
    }

    PSModel splitFeat = model.getPsModels().get(this.param.splitFeaturesName);
    splitFeat.increment(this.currentTree, splitFeatureVector);

    PSModel splitValue = model.getPsModels().get(this.param.splitValuesName);
    splitValue.increment(this.currentTree, splitValueVector);

    PSModel splitGain = model.getPsModels().get(this.param.splitGainsName);
    splitGain.increment(this.currentTree, splitGainVector);

    // 6. set phase to AFTER_SPLIT
    this.phase = GBDTPhase.AFTER_SPLIT;
    LOG.info(String.format("Find split cost: %d ms", System.currentTimeMillis() - startTime));

    // clock
    Set<String> needFlushMatrixSet = new HashSet<String>(3);
    needFlushMatrixSet.add(this.param.splitFeaturesName);
    needFlushMatrixSet.add(this.param.splitValuesName);
    needFlushMatrixSet.add(this.param.splitGainsName);
    needFlushMatrixSet.add(this.param.nodeGradStatsName);
    clockAllMatrix(needFlushMatrixSet, true);
  }

  public void afterSplit() throws Exception {
    LOG.info("------After split------");
    long startTime = System.currentTimeMillis();
    // 1. get split feature
    PSModel splitFeatModel = model.getPsModels().get(this.param.splitFeaturesName);
    DenseIntVector splitFeatureVec = (DenseIntVector) splitFeatModel.getRow(currentTree);

    // 2. get split value
    PSModel splitValueModel = model.getPsModels().get(this.param.splitValuesName);
    DenseDoubleVector splitValueVec = (DenseDoubleVector) splitValueModel.getRow(currentTree);

    // 3. get split gain
    PSModel splitGainModel = model.getPsModels().get(this.param.splitGainsName);
    DenseDoubleVector splitGainVec = (DenseDoubleVector) splitGainModel.getRow(currentTree);

    // 4. get node weight
    PSModel nodeGradStatsModel = model.getPsModels().get(this.param.nodeGradStatsName);
    DenseDoubleVector nodeGradStatsVec = (DenseDoubleVector) nodeGradStatsModel.getRow(currentTree);

    // LOG.info(String.format("Node grad stats: %s",
    // Arrays.toString(nodeGradStatsVec.getValues())));
    // 5. split node
    LOG.info(String.format("Split active node: %s", Arrays.toString(this.activeNode)));
    int[] preActiveNode = this.activeNode.clone();
    for (int nid = 0; nid < this.maxNodeNum; nid++) {
      if (preActiveNode[nid] == 1) {
        int splitFeature = splitFeatureVec.get(nid);
        float splitValue = (float) splitValueVec.get(nid);
        float splitGain = (float) splitGainVec.get(nid);
        float nodeSumGrad = (float) nodeGradStatsVec.get(nid);
        float nodeSumHess = (float) nodeGradStatsVec.get(nid + this.maxNodeNum);
        LOG.info(String.format(
            "Active node[%d]: split feature[%d] value[%f], lossChg[%f], sumGrad[%f], sumHess[%f]",
            nid, splitFeature, splitValue, splitGain, nodeSumGrad, nodeSumHess));
        if (splitFeature != -1) {
          // 5.1. set the children nodes of this node
          this.forest[this.currentTree].nodes.get(nid).setLeftChild(2 * nid + 1);
          this.forest[this.currentTree].nodes.get(nid).setRightChild(2 * nid + 2);
          // 5.2. set split info and grad stats to this node
          SplitEntry splitEntry = new SplitEntry(splitFeature, splitValue, splitGain);
          this.forest[this.currentTree].stats.get(nid).setSplitEntry(splitEntry);
          this.forest[this.currentTree].stats.get(nid).lossChg = splitGain;
          this.forest[this.currentTree].stats.get(nid).setStats(nodeSumGrad, nodeSumHess);
          // 5.2. create children nodes
          TNode leftChild = new TNode(2 * nid + 1, nid, -1, -1);
          TNode rightChild = new TNode(2 * nid + 2, nid, -1, -1);
          this.forest[this.currentTree].nodes.set(2 * nid + 1, leftChild);
          this.forest[this.currentTree].nodes.set(2 * nid + 2, rightChild);
          // 5.3. create node stats for children nodes, and add them to the tree
          RegTNodeStat leftChildStat = new RegTNodeStat(param);
          RegTNodeStat rightChildStat = new RegTNodeStat(param);
          float leftChildSumGrad = (float) nodeGradStatsVec.get(2 * nid + 1);
          float rightChildSumGrad = (float) nodeGradStatsVec.get(2 * nid + 2);
          float leftChildSumHess = (float) nodeGradStatsVec.get(2 * nid + 1 + this.maxNodeNum);
          float rightChildSumHess = (float) nodeGradStatsVec.get(2 * nid + 2 + this.maxNodeNum);
          leftChildStat.setStats(leftChildSumGrad, leftChildSumHess);
          rightChildStat.setStats(rightChildSumGrad, rightChildSumHess);
          this.forest[this.currentTree].stats.set(2 * nid + 1, leftChildStat);
          this.forest[this.currentTree].stats.set(2 * nid + 2, rightChildStat);
          // 5.4. reset instance position
          resetInsPos(nid, splitFeature, splitValue);
          // 5.5. add new active nodes if possible, inc depth, otherwise finish this tree
          // LOG.info(String.format("Current depth: %d, max depth: %d",
          // this.currentDepth, this.param.maxDepth));
          if (this.currentDepth < this.param.maxDepth - 1) {
            LOG.info(String.format("Add children nodes of node[%d]:[%d][%d] to active nodes",
                    nid, 2 * nid + 1, 2 * nid + 2));
            addActiveNode(2 * nid + 1);
            addActiveNode(2 * nid + 2);
          } else {
            // 5.6. set children nodes to leaf nodes
            LOG.info(String.format("Set children nodes of node[%d]:[%d][%d] to leaf nodes",
                    nid, 2 * nid + 1, 2 * nid + 2));
            setNodeToLeaf(2 * nid + 1, leftChildStat.baseWeight);
            setNodeToLeaf(2 * nid + 2, rightChildStat.baseWeight);
          }
        } else {
          // 5.7. set nid to leaf node
          setNodeToLeaf(nid, this.param.calcWeight(nodeSumGrad, nodeSumHess));
        }
        // 5.8. deactivate active node
        resetActiveTNodes(nid);
      }
    }
    // 6. check if there is active node, if not, finish current tree
    // boolean hasActive = hasActiveTNode();
    // if (!hasActive) {
    // finishCurrentTree();
    // this.phase = GBDTPhase.NEW_TREE;
    // } else {
    // finishCurrentDepth();
    // this.phase = GBDTPhase.RUN_ACTIVE;
    // }
    LOG.info(String.format("After split cost: %d ms", System.currentTimeMillis() - startTime));

    // clock
    Set<String> needFlushMatrixSet = new HashSet<String>(4);
    needFlushMatrixSet.add(this.param.splitFeaturesName);
    needFlushMatrixSet.add(this.param.splitValuesName);
    needFlushMatrixSet.add(this.param.splitGainsName);
    needFlushMatrixSet.add(this.param.nodeGradStatsName);
    clockAllMatrix(needFlushMatrixSet, true);
  }

  // split the span of one node, reset the instance position
  public void resetInsPos(int nid, int splitFeature, float splitValue) {
    LOG.info(String.format(
        "------Reset instance position of node[%d] split feature[%d] split value[%f]------", nid,
        splitFeature, splitValue));
    int nodePosStart = this.nodePosStart[nid];
    int nodePosEnd = this.nodePosEnd[nid];
    LOG.info(String.format("Node[%d] instance positions: [%d-%d]", nid, nodePosStart, nodePosEnd));
    int left = nodePosStart;
    int right = nodePosEnd;
    // in case this worker has no instance on this node
    if (left > right) {
      LOG.info("nodePosStart > nodePosEnd, maybe there is no instance on this node.");
      // set the span of left child
      this.nodePosStart[2 * nid + 1] = left;
      this.nodePosEnd[2 * nid + 1] = right;
      LOG.info(String.format("Node[%d] instance positions: [%d-%d]", 2 * nid + 1, left, right));
      // set the span of right child
      this.nodePosStart[2 * nid + 2] = left;
      this.nodePosEnd[2 * nid + 2] = right;
      LOG.info(String.format("Node[%d] instance positions: [%d-%d]", 2 * nid + 2, left, right));
      return;
    }
    while (right > left) {
      // 1. left to right, find the first instance that should be in the right child
      int leftInsIdx = this.instancePos[left];
      float leftValue = (float) this.dataMeta.instances.get(leftInsIdx).get(splitFeature);
      while (leftValue <= splitValue && left < right) {
        left++;
        leftInsIdx = this.instancePos[left];
        leftValue = (float) this.dataMeta.instances.get(leftInsIdx).get(splitFeature);
      }
      // 2. right to left, find the first instance that should be in the left child
      int rightInsIdx = this.instancePos[right];
      float rightValue = (float) this.dataMeta.instances.get(rightInsIdx).get(splitFeature);
      while (rightValue > splitValue && right > left) {
        right--;
        rightInsIdx = this.instancePos[right];
        rightValue = (float) this.dataMeta.instances.get(rightInsIdx).get(splitFeature);
      }
      // 3. swap two instances
      if (right > left) {
        this.instancePos[left] = rightInsIdx;
        this.instancePos[right] = leftInsIdx;
      }
    }
    // 4. find the cut pos
    int curInsIdx = this.instancePos[left];
    float curValue = (float) this.dataMeta.instances.get(curInsIdx).get(splitFeature);
    int cutPos = (curValue >= splitValue) ? left : left + 1; // the first instance that is larger
                                                             // than the split value
    // 5. set the span of left child
    this.nodePosStart[2 * nid + 1] = nodePosStart;
    this.nodePosEnd[2 * nid + 1] = cutPos - 1;
    LOG.info(String.format("Node[%d] instance positions: [%d-%d]", 2 * nid + 1, nodePosStart,
        cutPos - 1));
    // 6. set the span of right child
    this.nodePosStart[2 * nid + 2] = cutPos;
    this.nodePosEnd[2 * nid + 2] = nodePosEnd;
    LOG.info(String.format("Node[%d] instance positions: [%d-%d]", 2 * nid + 2, cutPos, nodePosEnd));
  }

  // set tree node to active
  private void addActiveNode(int nid) {
    this.activeNode[nid] = 1;
    this.activeNodeStat[nid] = 0;
  }

  // set node to leaf
  private void setNodeToLeaf(int nid, float nodeWeight) {
    LOG.info(String.format("Set node[%d] to leaf node, leaf weight[%f]", nid, nodeWeight));
    this.forest[currentTree].nodes.get(nid).chgToLeaf();
    this.forest[currentTree].nodes.get(nid).setLeafValue(nodeWeight);

  }

  // set node to inactive
  private void resetActiveTNodes(int nid) {
    this.activeNode[nid] = 0;
    this.activeNodeStat[nid] = 0;
  }

  // finish current tree
  public void finishCurrentTree() {
    // calculate the error
    eval();
    predict();
    this.currentTree++;
    this.currentDepth = 1;
  }

  // finish current depth
  public void finishCurrentDepth() {
    this.currentDepth++;
  }

  // set the tree phase
  public void setPhase(int phase) {
    this.phase = phase;
  }

  // check if there is active node
  public boolean hasActiveTNode() {
    LOG.info(String.format("Check active node: %s", Arrays.toString(activeNode)));
    boolean hasActive = false;
    for (int isActive : this.activeNode) {
      if (isActive == 1) {
        hasActive = true;
        break;
      }
    }
    return hasActive;
  }

  // check if finish all the tree
  public boolean isFinished() {
    LOG.info(String.format("Check if finished, cur tree[%d], max tree[%d]", this.currentTree,
        this.param.treeNum));
    return (this.currentTree >= this.param.treeNum);
  }

  // update node's grad stats on PS
  // called during splitting in DistributedHistHelper, update the grad stats of children nodes after
  // find the best split
  // the root node's stats is updated by leader worker by one time
  public void updateNodeGradStats(int nid, GradStats gradStats) throws Exception {
    LOG.info(String.format("Update gradStats of node[%d]: sumGrad[%f], sumHess[%f]", nid,
        gradStats.sumGrad, gradStats.sumHess));
    // // 1.1. get the index of the grad and hess
    // int[] nodeIndice = { nid, nid + this.activeNode.length };
    // // 1.2. get the grad sum and hess sum
    // double[] weightValue = { gradStats.sumGrad, gradStats.sumHess };
    // 1.3. create the update
    DenseDoubleVector vec = new DenseDoubleVector(2 * this.activeNode.length);
    vec.set(nid, gradStats.sumGrad);
    vec.set(nid + this.activeNode.length, gradStats.sumHess);
    // for (int i = 0; i < nodeIndice.length; i++) {
    // vec.set(nodeIndice[i], weightValue[i]);
    // }
    // 1.4. push the update to PS
    PSModel nodeGradStats = this.model.getPsModels().get(this.param.nodeGradStatsName);
    nodeGradStats.increment(this.currentTree, vec);
  }

  public void updateInsPreds() throws Exception {
    LOG.info("------Update instance predictions------");
    long startTime = System.currentTimeMillis();
    int nodeNum = this.forest[currentTree].nodes.size();
    for (int nid = 0; nid < nodeNum; nid++) {
      if (null != this.forest[currentTree].nodes.get(nid)
          && this.forest[currentTree].nodes.get(nid).isLeaf()) {
        float weight = this.forest[currentTree].nodes.get(nid).getLeafValue();
        LOG.info(String.format("Leaf weight of node[%d]: %f", nid, weight));
        int nodePosStart = this.nodePosStart[nid];
        int nodePosEnd = this.nodePosEnd[nid];
        for (int i = nodePosStart; i < nodePosEnd; i++) {
          int insIdx = this.instancePos[i];
          this.dataMeta.preds[insIdx] += this.param.learningRate * weight;
          // LOG.info(String.format("Update the prediction of instance[%d] to %f, label[%f]",
          // insIdx, this.dataMeta.preds[insIdx], this.dataMeta.labels[insIdx]));
        }
      }
    }

    LOG.info(String.format("Update instance predictions cost: %d ms", System.currentTimeMillis() - startTime));
  }

  public void updateLeafPreds() throws Exception {
    LOG.info("------Update leaf node predictions------");
    long startTime = System.currentTimeMillis();
    int nodeNum = this.forest[currentTree].nodes.size();
    DenseDoubleVector vec = new DenseDoubleVector(this.maxNodeNum);
    for (int nid = 0; nid < nodeNum; nid++) {
      if (null != this.forest[currentTree].nodes.get(nid)
              && this.forest[currentTree].nodes.get(nid).isLeaf()) {
        float weight = this.forest[currentTree].nodes.get(nid).getLeafValue();
        LOG.info(String.format("Leaf weight of node[%d]: %f", nid, weight));
        vec.set(nid, weight);
      }
    }
    PSModel nodePreds = this.model.getPsModels().get(this.param.nodePredsName);
    nodePreds.increment(this.currentTree, vec);

    Set<String> needFlushMatrixSet = new HashSet<String>(1);
    if (taskContext.getTaskIndex() == 0) {
      // the leader task adds node prediction to flush list
      needFlushMatrixSet.add(this.param.nodePredsName);
    }
    clockAllMatrix(needFlushMatrixSet, true);

    LOG.info(String.format("Update leaf node predictions cost: %d ms", System.currentTimeMillis() - startTime));
  }

  public void eval() {
    LOG.info("------Evaluation------");
    long startTime = System.currentTimeMillis();
    Metric metric = new LogisErrorMetric();
    float error = metric.eval(this.dataMeta.preds, this.dataMeta.labels);
    LOG.info(String.format("Error after tree[%d]: %f", this.currentTree, error));
    LOG.info(String.format("Evaluation cost: %d ms", System.currentTimeMillis() - startTime));
  }

  public void predict() {
    LOG.info("------Predict------");
    long startTime = System.currentTimeMillis();
    PSModel splitFeat = this.model.getPsModels().get(this.param.splitFeaturesName);
    PSModel splitValue = this.model.getPsModels().get(this.param.splitValuesName);
    PSModel nodePreds = this.model.getPsModels().get(this.param.nodePredsName);

    for (int nid = 0; nid < this.forest[currentTree].nodes.size(); nid++) {
      if (null != this.forest[currentTree].nodes.get(nid)) {
        float weight = this.forest[currentTree].nodes.get(nid).getLeafValue();
        LOG.info(String.format("Leaf weight of node[%d]: %f", nid, weight));
      }
    }

    TIntVector splitFeatVec = (TIntVector) splitFeat.getRow(this.currentTree);
    TDoubleVector splitValueVec = (TDoubleVector) splitValue.getRow(this.currentTree);
    TDoubleVector nodePredVec = (TDoubleVector) nodePreds.getRow(this.currentTree);
    LOG.info(String.format("Prediction of tree[%d]: %s",
            this.currentTree, Arrays.toString(nodePredVec.getValues())));
    for (int insIdx = 0; insIdx < this.validDataMeta.numRow; insIdx++) {
      double curPred = treePred(splitFeatVec, splitValueVec, nodePredVec,
              this.validDataMeta.instances.get(insIdx));
      //LOG.info("Prediction of current tree: " + curPred);
      if (insIdx == 1000) {
        LOG.info(String.format("Prediction of the %d-th instance: " +
                "old pred[%f], cur tree[%f]",
                insIdx, this.validDataMeta.preds[insIdx], curPred));
      }
      this.validDataMeta.preds[insIdx] += this.param.learningRate * curPred;
    }

    Metric metric = new LogisErrorMetric();
    float error = metric.eval(this.validDataMeta.preds, this.validDataMeta.labels);
    LOG.info(String.format("Error after tree[%d]: %f", this.currentTree, error));
    LOG.info(String.format("Evaluation cost: %d ms", System.currentTimeMillis() - startTime));
  }

  public double treePred(TIntVector splitFeatVec, TDoubleVector splitValueVec,
                        TDoubleVector nodePredVec, SparseDoubleSortedVector ins) {
    double pred;
    assert splitFeatVec.getDimension() == splitValueVec.getDimension()
            && splitValueVec.getDimension() == nodePredVec.getDimension();
    int nid = 0;
    int splitFeat = splitFeatVec.get(nid);
    double splitValue = splitValueVec.get(nid);
    pred = this.forest[currentTree].nodes.get(nid).getLeafValue();

    while (null != this.forest[this.currentTree].nodes.get(nid)
            && !this.forest[this.currentTree].nodes.get(nid).isLeaf()
            && -1 != splitFeat && nid < splitFeatVec.getDimension()) {
      if (ins.get(splitFeat) <= splitValue) {
        nid = 2 * nid + 1;
      } else {
        nid = 2 * nid + 2;
      }
      splitFeat = splitFeatVec.get(nid);
      splitValue = splitValueVec.get(nid);
      pred = this.forest[currentTree].nodes.get(nid).getLeafValue();
    }

    return pred;
  }

}