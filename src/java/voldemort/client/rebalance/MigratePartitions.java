package voldemort.client.rebalance;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.cluster.Cluster;
import voldemort.server.VoldemortConfig;
import voldemort.server.protocol.admin.AsyncOperationStatus;
import voldemort.store.StoreDefinition;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.metadata.MetadataStore.VoldemortState;
import voldemort.utils.CmdUtils;
import voldemort.utils.DaemonThreadFactory;
import voldemort.utils.Pair;
import voldemort.utils.RebalanceUtils;
import voldemort.utils.Time;
import voldemort.utils.Utils;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class MigratePartitions {

    private static Logger logger = Logger.getLogger(MigratePartitions.class);
    private final AdminClient adminClient;
    private final List<String> storeNames;
    private List<Integer> stealerNodeIds;
    private final HashMap<Integer, RebalanceNodePlan> stealerNodePlans;
    private final HashMap<Integer, List<RebalancePartitionsInfo>> donorNodePlans;
    private final HashMap<Integer, Versioned<String>> donorStates;
    private final boolean transitionToNormal;
    private boolean simulation = false;
    private final ExecutorService executor;
    private String checkpointFolder = "/tmp/checkpointFolder";

    public MigratePartitions(Cluster currentCluster,
                             Cluster targetCluster,
                             List<StoreDefinition> currentStoreDefs,
                             List<StoreDefinition> targetStoreDefs,
                             AdminClient adminClient,
                             VoldemortConfig voldemortConfig,
                             List<Integer> stealerNodeIds,
                             int parallelism,
                             boolean transitionToNormal,
                             boolean simulation,
                             String checkpointFolder) {

        this(currentCluster,
             targetCluster,
             currentStoreDefs,
             targetStoreDefs,
             adminClient,
             voldemortConfig,
             stealerNodeIds,
             parallelism,
             transitionToNormal);
        this.checkpointFolder = checkpointFolder;
        this.simulation = simulation;
    }

    /**
     * 
     * @param currentCluster The cluster as it is now
     * @param targetCluster The cluster as we would want in the future
     * @param currentStoreDefs The current list of store definitions
     * @param targetStoreDefs The final list of store definitions
     * @param adminClient Admin client which we'll use to query
     * @param voldemortConfig Voldemort configurations
     * @param stealerNodeIds The list of stealer nodes we'll work on. If null is
     *        sent will work on all generated by plan
     * @param parallelism How many stealer nodes to do in parallel
     * @param transitionToNormal After the complete migration of partitions, do
     *        you want to transition back to normal state?
     */
    public MigratePartitions(Cluster currentCluster,
                             Cluster targetCluster,
                             List<StoreDefinition> currentStoreDefs,
                             List<StoreDefinition> targetStoreDefs,
                             AdminClient adminClient,
                             VoldemortConfig voldemortConfig,
                             List<Integer> stealerNodeIds,
                             int parallelism,
                             boolean transitionToNormal) {
        this.adminClient = Utils.notNull(adminClient);
        this.stealerNodeIds = stealerNodeIds;
        this.transitionToNormal = transitionToNormal;
        this.executor = Executors.newFixedThreadPool(parallelism,
                                                     new DaemonThreadFactory("migrate"));
        MigratePartitionsPlan plan = new MigratePartitionsPlan(currentCluster,
                                                               targetCluster,
                                                               currentStoreDefs,
                                                               targetStoreDefs);

        logger.info(" ====== Migrate partitions cluster plan by stealer node id ======= ");
        logger.info(plan);

        this.stealerNodePlans = plan.getRebalancingTaskQueuePerNode();
        if(this.stealerNodeIds == null) {
            this.stealerNodeIds = Lists.newArrayList(stealerNodePlans.keySet());
        }

        this.storeNames = RebalanceUtils.getStoreNames(targetStoreDefs);

        // Converts the stealer node plans into a mapping of plans on a per
        // donor node basis. This is required for each donor node to know which
        // partitions are being grandfathered.
        this.donorNodePlans = Maps.newHashMap();
        for(int stealerNodeId: this.stealerNodeIds) {
            RebalanceNodePlan nodePlan = this.stealerNodePlans.get(stealerNodeId);
            if(nodePlan == null)
                continue;
            for(RebalancePartitionsInfo info: nodePlan.getRebalanceTaskList()) {
                List<RebalancePartitionsInfo> donorPlan = donorNodePlans.get(info.getDonorId());
                if(donorPlan == null) {
                    donorPlan = Lists.newArrayList();
                    donorNodePlans.put(info.getDonorId(), donorPlan);
                }
                donorPlan.add(info);
            }
        }
        this.donorStates = Maps.newHashMap();

        logger.info(" ====== Migrate partitions cluster plan by donor node id ======= ");
        for(int donorNodeId: donorNodePlans.keySet()) {
            logger.info("Plan for donor node id " + donorNodeId + " => ");
            List<RebalancePartitionsInfo> list = donorNodePlans.get(donorNodeId);
            for(RebalancePartitionsInfo stealInfo: list) {
                logger.info(stealInfo);
            }
        }
        logger.info(" ============================================== ");
    }

    /**
     * @return Map of donor node id to their corresponding list of rebalance
     *         info per partition
     */
    public HashMap<Integer, List<RebalancePartitionsInfo>> getDonorNodePlan() {
        return donorNodePlans;
    }

    /**
     * Update the state of all donor nodes to grandfather state
     * 
     */
    public void changeToGrandfather() {
        for(int donorNodeId: donorNodePlans.keySet()) {
            logger.info("Transitioning " + donorNodeId + " to grandfathering state");
            if(!simulation) {
                Versioned<String> serverState = adminClient.updateGrandfatherMetadata(donorNodeId,
                                                                                      donorNodePlans.get(donorNodeId));
                if(!VoldemortState.valueOf(serverState.getValue())
                                  .equals(MetadataStore.VoldemortState.GRANDFATHERING_SERVER)) {
                    throw new VoldemortException("Node "
                                                 + donorNodeId
                                                 + " is not in normal state to perform grandfathering");
                }
                donorStates.put(donorNodeId, serverState);
            }
            logger.info("Successfully transitioned " + donorNodeId + " to grandfathering state");

        }
        logger.info(" ============================================== ");
    }

    /**
     * Update the state of all donor nodes in grandfathering state back to
     * normal
     * 
     */
    public void changeToNormal() {
        for(int donorNodeId: donorStates.keySet()) {
            logger.info("Rolling back state of " + donorNodeId + " to normal");
            try {
                VectorClock clock = (VectorClock) donorStates.get(donorNodeId).getVersion();
                adminClient.updateRemoteMetadata(donorNodeId,
                                                 MetadataStore.SERVER_STATE_KEY,
                                                 Versioned.value(MetadataStore.VoldemortState.NORMAL_SERVER.toString(),
                                                                 clock.incremented(donorNodeId,
                                                                                   System.currentTimeMillis())));
            } catch(Exception e) {
                logger.error("Rolling back state for " + donorNodeId + " failed");
            }
        }
    }

    public void migrate() {

        final HashMultimap<String, RebalancePartitionsInfo> completedTasks = HashMultimap.create();
        final AtomicInteger completed = new AtomicInteger(0);

        // Parse the check point file and check if any tasks are done
        if(new File(checkpointFolder).exists()) {

            if(!Utils.isReadableDir(checkpointFolder)) {
                logger.error("The checkpoint folder " + checkpointFolder + " cannot be read");
                return;
            }
            // Check point folder exists, lets parse the individual
            // completedTasks
            logger.info("Check point file exists, parsing it...");
            for(String storeName: storeNames) {
                if(!new File(checkpointFolder, storeName).exists()) {
                    logger.info("No check point file for store " + storeName);
                    completedTasks.putAll(storeName, new ArrayList<RebalancePartitionsInfo>());
                } else {
                    // Some tasks for this store got over
                    List<String> completedTasksLines = null;

                    // Check if any previous tasks for
                    try {
                        completedTasksLines = FileUtils.readLines(new File(checkpointFolder,
                                                                           storeName));
                    } catch(IOException e) {
                        logger.error("Could not read the check point file for store name "
                                     + storeName, e);
                        return;
                    }

                    for(String completedTaskLine: completedTasksLines) {
                        completedTasks.put(storeName,
                                           RebalancePartitionsInfo.create(completedTaskLine));
                    }
                    logger.info("Completed tasks for " + storeName + " = "
                                + completedTasks.get(storeName).size());
                    completed.addAndGet(completedTasks.get(storeName).size());
                }
            }
        } else {
            logger.info("Check point folder does not exist. Starting a new one at "
                        + checkpointFolder);
            Utils.mkdirs(new File(checkpointFolder));
        }
        logger.info(" ============================================== ");

        if(donorNodePlans.size() == 0) {
            logger.info("Nothing to move around");
            return;
        }

        int t = 0;
        for(List<RebalancePartitionsInfo> rebalancePartitionsInfos: donorNodePlans.values())
            t += rebalancePartitionsInfos.size();
        final int total = t * storeNames.size();

        final long startedAt = System.currentTimeMillis();

        /**
         * Lets move all the donor nodes into grandfathering state. First
         * generate all donor node ids and corresponding migration plans
         */
        logger.info("Changing state of donor nodes " + donorNodePlans.keySet());
        final CountDownLatch latch = new CountDownLatch(stealerNodeIds.size());
        try {
            changeToGrandfather();

            /**
             * Do all the stealer nodes sequentially, while each store can be
             * done in parallel for all the respective donor nodes
             */

            final AtomicInteger numStealersCompleted = new AtomicInteger(0);
            for(final int stealerNodeId: stealerNodeIds) {
                executor.submit(new Runnable() {

                    public void run() {
                        try {
                            RebalanceNodePlan nodePlan = stealerNodePlans.get(stealerNodeId);
                            if(nodePlan == null) {
                                logger.info("No plan for stealer node id " + stealerNodeId);
                                return;
                            }
                            List<RebalancePartitionsInfo> partitionInfo = nodePlan.getRebalanceTaskList();

                            logger.info("Working on stealer node id " + stealerNodeId);
                            for(String storeName: storeNames) {

                                Set<Pair<Integer, Integer>> pending = Sets.newHashSet();
                                HashMap<Pair<Integer, Integer>, RebalancePartitionsInfo> pendingTasks = Maps.newHashMap();

                                for(RebalancePartitionsInfo r: partitionInfo) {
                                    if(completedTasks.get(storeName).contains(r)) {
                                        logger.info("-- Not doing task from donorId "
                                                    + r.getDonorId() + " to " + r.getStealerId()
                                                    + " with store " + storeName
                                                    + " since it is already done");
                                        continue;
                                    }
                                    logger.info("-- Started migration from donorId "
                                                + r.getDonorId() + " to " + stealerNodeId
                                                + " for store " + storeName);
                                    if(!simulation) {
                                        int attemptId = adminClient.migratePartitions(r.getDonorId(),
                                                                                      stealerNodeId,
                                                                                      storeName,
                                                                                      r.getPartitionList(),
                                                                                      null);
                                        pending.add(Pair.create(r.getDonorId(), attemptId));
                                        pendingTasks.put(Pair.create(r.getDonorId(), attemptId), r);
                                    }
                                }

                                while(!pending.isEmpty()) {
                                    long delay = 1000;
                                    Set<Pair<Integer, Integer>> currentPending = ImmutableSet.copyOf(pending);
                                    for(Pair<Integer, Integer> pair: currentPending) {
                                        AsyncOperationStatus status = adminClient.getAsyncRequestStatus(stealerNodeId,
                                                                                                        pair.getSecond());
                                        logger.info("Status of move from " + pair.getFirst()
                                                    + " to " + stealerNodeId + ": "
                                                    + status.getStatus());
                                        if(status.hasException()) {
                                            throw new VoldemortException(status.getException());
                                        }
                                        if(status.isComplete()) {
                                            logger.info("-- Completed migration from donorId "
                                                        + pair.getFirst() + " to " + stealerNodeId
                                                        + " for store " + storeName);
                                            logger.info("-- " + completed.incrementAndGet()
                                                        + " out of " + total + " tasks completed");
                                            pending.remove(pair);

                                            // Write the task to file
                                            // immediately
                                            BufferedWriter out = null;
                                            try {
                                                out = new BufferedWriter(new FileWriter(new File(checkpointFolder,
                                                                                                 storeName),
                                                                                        true));
                                                out.write(pendingTasks.get(pair).toJsonString()
                                                          + "\n");
                                            } catch(Exception e) {
                                                logger.error("Failure while writing check point for store "
                                                             + storeName + ". Emitting it here ");
                                                logger.error("Checkpoint failure ("
                                                             + storeName
                                                             + "):"
                                                             + pendingTasks.get(pair)
                                                                           .toJsonString());
                                            } finally {
                                                if(out != null) {
                                                    out.flush();
                                                    out.close();
                                                }
                                            }

                                            long velocity = (System.currentTimeMillis() - startedAt)
                                                            / completed.get();
                                            long eta = (total - completed.get()) * velocity
                                                       / Time.MS_PER_SECOND;
                                            logger.info("-- Estimated " + eta
                                                        + " seconds until completion");
                                        }
                                    }
                                    try {
                                        Thread.sleep(delay);
                                        if(delay < 30000)
                                            delay *= 2;
                                    } catch(InterruptedException e) {
                                        throw new VoldemortException(e);
                                    }
                                }
                            }
                        } catch(Exception e) {
                            logger.error("Exception for stealer node " + stealerNodeId, e);
                            while(latch.getCount() > 0)
                                latch.countDown();
                            executor.shutdownNow();
                            throw new VoldemortException(e);
                        } finally {
                            latch.countDown();
                            logger.info("Number of stealers completed - "
                                        + numStealersCompleted.incrementAndGet());
                        }
                    }
                });
            }
            latch.await();
        } catch(Exception e) {
            logger.error("Exception in full process", e);
            executor.shutdownNow();
            throw new VoldemortException(e);
        } finally {
            // Move all nodes in grandfathered state back to normal
            if(donorStates != null && transitionToNormal) {
                changeToNormal();
           }
            executor.shutdown();

        }
    }

    public static void main(String[] args) throws IOException {

        OptionParser parser = new OptionParser();
        parser.accepts("help", "print help information");
        parser.accepts("parallelism", "Parallelism [Default 2]")
              .withRequiredArg()
              .describedAs("parallelism")
              .ofType(Integer.class);
        parser.accepts("target-cluster-xml", "[REQUIRED] target cluster xml file location")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("stores-xml", "[REQUIRED] stores xml file location")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("target-stores-xml", "stores xml file location if changed")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("cluster-xml", "[REQUIRED] cluster xml file location")
              .withRequiredArg()
              .describedAs("path");
        parser.accepts("stealer-node-ids", "Comma separated node ids [Default - all]")
              .withRequiredArg()
              .ofType(Integer.class)
              .withValuesSeparatedBy(',');
        parser.accepts("transition-to-normal",
                       "At the end of migration do we want to transition back to normal state? [Default-false]");
        parser.accepts("simulation", "Run the full process as simulation");
        parser.accepts("checkpoint-folder",
                       "Give a folder of completed tasks [ Or if running for first time, empty folder to dump checkpoints ]. "
                               + "If non-empty, these tasks won't be executed")
              .withRequiredArg()
              .describedAs("file-name");

        OptionSet options = parser.parse(args);

        if(options.has("help")) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        Set<String> missing = CmdUtils.missing(options,
                                               "cluster-xml",
                                               "stores-xml",
                                               "target-cluster-xml",
                                               "checkpoint-folder");
        if(missing.size() > 0) {
            System.err.println("Missing required arguments: " + Joiner.on(", ").join(missing));
            parser.printHelpOn(System.err);
            System.exit(1);
        }

        String targetClusterFile = (String) options.valueOf("target-cluster-xml");
        String currentClusterFile = (String) options.valueOf("cluster-xml");
        String currentStoresFile = (String) options.valueOf("stores-xml");
        String targetStoresFile = currentStoresFile;
        final String checkpointFolder = (String) options.valueOf("checkpoint-folder");
        int parallelism = CmdUtils.valueOf(options, "parallelism", 2);
        boolean transitionToNormal = options.has("transition-to-normal");
        boolean simulation = options.has("simulation");

        if(options.has("target-stores-xml")) {
            targetStoresFile = (String) options.valueOf("target-stores-xml");
        }

        if(!Utils.isReadableFile(targetClusterFile) || !Utils.isReadableFile(currentClusterFile)
           || !Utils.isReadableFile(currentStoresFile) || !Utils.isReadableFile(targetStoresFile)) {
            System.err.println("Could not read metadata files from path provided");
            parser.printHelpOn(System.err);
            System.exit(1);
        }

        List<Integer> stealerNodeIds = null;
        if(options.has("stealer-node-ids")) {
            stealerNodeIds = Utils.uncheckedCast(options.valueOf("stealer-node-ids"));
        }

        AdminClient adminClient = null;
        try {
            VoldemortConfig voldemortConfig = createTempVoldemortConfig();
            Cluster currentCluster = new ClusterMapper().readCluster(new BufferedReader(new FileReader(currentClusterFile)));
            Cluster targetCluster = new ClusterMapper().readCluster(new BufferedReader(new FileReader(targetClusterFile)));
            adminClient = RebalanceUtils.createTempAdminClient(voldemortConfig,
                                                               currentCluster,
                                                               targetCluster.getNumberOfNodes(),
                                                               1);

            List<StoreDefinition> currentStoreDefs = new StoreDefinitionsMapper().readStoreList(new BufferedReader(new FileReader(currentStoresFile)));
            List<StoreDefinition> targetStoreDefs = new StoreDefinitionsMapper().readStoreList(new BufferedReader(new FileReader(targetStoresFile)));

            final MigratePartitions migratePartitions = new MigratePartitions(currentCluster,
                                                                              targetCluster,
                                                                              currentStoreDefs,
                                                                              targetStoreDefs,
                                                                              adminClient,
                                                                              voldemortConfig,
                                                                              stealerNodeIds,
                                                                              parallelism,
                                                                              transitionToNormal,
                                                                              simulation,
                                                                              checkpointFolder);

            migratePartitions.migrate();

        } catch(Exception e) {
            logger.error("Error in migrate partitions", e);
        } finally {
            if(adminClient != null)
                adminClient.stop();
        }

    }

    public static VoldemortConfig createTempVoldemortConfig() {
        File temp = new File(System.getProperty("java.io.tmpdir"),
                             Integer.toString(new Random().nextInt()));
        temp.delete();
        temp.mkdir();
        temp.deleteOnExit();
        VoldemortConfig config = new VoldemortConfig(0, temp.getAbsolutePath());
        new File(config.getMetadataDirectory()).mkdir();
        return config;
    }
}
