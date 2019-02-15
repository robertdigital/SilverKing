package com.ms.silverking.cloud.dht.daemon.storage.convergence.management;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.KeeperException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import com.ms.silverking.cloud.common.OwnerQueryMode;
import com.ms.silverking.cloud.dht.client.ClientDHTConfigurationProvider;
import com.ms.silverking.cloud.dht.client.ClientException;
import com.ms.silverking.cloud.dht.daemon.ReplicaNaiveIPPrioritizer;
import com.ms.silverking.cloud.dht.gridconfig.SKGridConfiguration;
import com.ms.silverking.cloud.dht.management.MetaUtil;
import com.ms.silverking.cloud.dht.management.MetaUtilOptions;
import com.ms.silverking.cloud.dht.meta.DHTRingCurTargetZK;
import com.ms.silverking.cloud.gridconfig.GridConfiguration;
import com.ms.silverking.cloud.meta.ExclusionSet;
import com.ms.silverking.cloud.meta.ExclusionZK;
import com.ms.silverking.cloud.meta.ServerSetExtensionZK;
import com.ms.silverking.cloud.toporing.InstantiatedRingTree;
import com.ms.silverking.cloud.toporing.ResolvedReplicaMap;
import com.ms.silverking.cloud.toporing.RingEntry;
import com.ms.silverking.cloud.toporing.SingleRingZK;
import com.ms.silverking.cloud.toporing.meta.MetaClient;
import com.ms.silverking.collection.Pair;
import com.ms.silverking.collection.Triple;
import com.ms.silverking.log.Log;
import com.ms.silverking.net.IPAndPort;
import com.ms.silverking.numeric.NumUtil;

public class RingIntegrityCheck {
	private final SKGridConfiguration	gc;
	private final PrintStream			out;
	private final MetaUtil				metaUtil;
	private final com.ms.silverking.cloud.dht.meta.MetaClient	dhtMC;
	private final com.ms.silverking.cloud.meta.MetaClient		cloudMC;
	private final String				ringParentName; // FUTURE - remove to make functional
	private final DHTRingCurTargetZK	dhtRingCurTargetZK;
	private final Triple<String, Long, Long>	curRingAndVersionPair;
	private final ResolvedReplicaMap	rMap;
	
	public RingIntegrityCheck(SKGridConfiguration gc, Triple<String, Long, Long> ring) throws IOException, KeeperException, ClientException {
		this.gc = gc;
		this.out = System.out;
		
		System.out.printf("\t%s\n", gc.getClientDHTConfiguration().getName());
		System.out.printf("\t%s\n", gc.getClientDHTConfiguration().getZKConfig());
		metaUtil = new MetaUtil(gc.getClientDHTConfiguration().getName(), gc.getClientDHTConfiguration().getZKConfig(), MetaUtilOptions.dhtVersionUnspecified);
		dhtMC = metaUtil.getDHTMC();
		cloudMC = metaUtil.getRingMC().createCloudMC();
		ringParentName = metaUtil.getRingConfiguration().getRingParentName();
		dhtRingCurTargetZK = new DHTRingCurTargetZK(dhtMC, dhtMC.getDHTConfiguration());
		curRingAndVersionPair = dhtRingCurTargetZK.getCurRingAndVersionPair();
		if (ring == null) {
			ring = curRingAndVersionPair;
		}
		rMap = readReplicaMap(ring);
	}
	
	private ExclusionSet getCurrentExclusionSet() throws KeeperException {
		ExclusionZK		exclusionZK;
		ExclusionSet	serverExclusionSet;
		ExclusionSet	instanceExclusionSet;
		
		exclusionZK = new ExclusionZK(cloudMC);
		serverExclusionSet = exclusionZK.readLatestFromZK();
        try {
        	instanceExclusionSet = new ExclusionSet(new ServerSetExtensionZK(dhtMC, dhtMC.getMetaPaths().getInstanceExclusionsPath()).readLatestFromZK());
        } catch (Exception e) {
        	Log.warning("No instance ExclusionSet found");
        	instanceExclusionSet = ExclusionSet.emptyExclusionSet(0);
        }
        return ExclusionSet.union(serverExclusionSet, instanceExclusionSet);
	}
	
	public int checkIntegrity(ExclusionSet exclusionSet, boolean verbose) {
		if (verbose) {
			System.out.printf("exclusionSet %s\n", exclusionSet);
		}
		return checkIntegrity(rMap, exclusionSet, verbose);
	}
	
	private int checkIntegrity(ResolvedReplicaMap rMap, ExclusionSet exclusionSet, boolean verbose) {
		int	setsExcluded;
		int	minReplicaSetSize;
		
		minReplicaSetSize = Integer.MAX_VALUE;
		setsExcluded = 0;
		for (Set<IPAndPort> replicaSet : rMap.getReplicaSets()) {
			boolean	allExcluded;
			int		numExcluded;
			int		setSize;
			
			setSize = 0;
			numExcluded = 0;
			allExcluded = true;
			//System.out.printf("replicaSet %s\n", replicaSet);
			for (IPAndPort member : replicaSet) {
				//System.out.printf("member %s %s\n", member.getIPAsString(), exclusionSet.contains(member.getIPAsString()));
				if (!exclusionSet.contains(member.getIPAsString())) {
					allExcluded = false;
					++setSize;
				} else {
					++numExcluded;
				}
			}
			if (setSize < minReplicaSetSize) {
				minReplicaSetSize = setSize;
			}
			if (verbose) {
				System.out.printf("%s\t%d\n", replicaSet, numExcluded);
			}
			if (allExcluded) {
				++setsExcluded;
				if (verbose) {
					System.out.printf("Set is excluded: %s\n", replicaSet);
				}
			}
		}
		if (verbose) {
			System.out.printf("setsExcluded: %d\n", setsExcluded);
		}
		return minReplicaSetSize;
	}
	
	private Map<IPAndPort,Long> getAllocationMap(ResolvedReplicaMap map) {
		Map<IPAndPort,Long>	am;
		
		am = new HashMap<>();
		for (RingEntry entry : map.getEntries()) {
			for (IPAndPort replica : entry.getOwnersIPList(OwnerQueryMode.Primary)) {
				Long	curAllocation;
				
				curAllocation = am.get(replica);
				if (curAllocation == null) {
					curAllocation = new Long(0);
				}
				curAllocation += entry.getRegion().getSize();
				am.put(replica, curAllocation);
			}
		}
		return am;
	}
	
	private ResolvedReplicaMap readReplicaMap(Triple<String,Long,Long> ring) throws IOException, KeeperException {
		return readTree(ring).getResolvedMap(ringParentName, new ReplicaNaiveIPPrioritizer());
	}
	
	private InstantiatedRingTree readTree(Triple<String,Long,Long> ring) throws IOException, KeeperException {
		MetaClient	ringMC;
		long	ringConfigVersion;
		long	configInstanceVersion;
		InstantiatedRingTree	ringTree;
		
		ringConfigVersion = ring.getTail().getV1();
		configInstanceVersion = ring.getTail().getV2();
		
		ringMC = metaUtil.getRingMC();
		
		ringTree = SingleRingZK.readTree(ringMC, ringConfigVersion, configInstanceVersion);
		return ringTree;
	}
	
	private static Triple<String,Long,Long> getRingAndVersionPair(String ringNameAndVersionPair) {
		String[]	s;
		
		s = ringNameAndVersionPair.split(",");
		return new Triple<>(s[0], Long.parseLong(s[1]), Long.parseLong(s[2]));
	}	
	
	private ExclusionSet createRandomExclusionSet(int numServers, ExclusionSet baseExclusionSet) {
		List<IPAndPort>	replicas;
		Set<String>		newlyExcludedServers;
		
		if (numServers > rMap.allReplicas().size()) {
			throw new RuntimeException("numServers > rMap.allReplicas().size()");
		}
		replicas = new ArrayList<>();
		replicas.addAll(rMap.allReplicas());
		Collections.shuffle(replicas);
		newlyExcludedServers = new HashSet<>();
		for (int i = 0; i < numServers; i++) {
			newlyExcludedServers.add(replicas.get(i).getIPAsString());
		}
		return baseExclusionSet.add(newlyExcludedServers);
	}
	
	private double estimateLoss(int numServers, int numSimulations, ExclusionSet baseExclusionSet) {
		int	numGood;
		int	numBad;
		
		numGood = 0;
		numBad = 0;
		for (int i = 0; i < numSimulations; i++) {
			ExclusionSet	sExclusionSet;
			
			sExclusionSet = createRandomExclusionSet(numServers, baseExclusionSet);
			if (checkIntegrity(sExclusionSet, false) > 0) {
				++numGood;
			} else {
				++numBad;
			}
		}
		return (double)numBad / (double)numSimulations;
	}
	
	public List<Double> estimateLoss(Pair<List<Integer>,Integer> lossEstimationParameters, ExclusionSet baseExclusionSet) {
		List<Double>	pLoss;
		
		pLoss = new ArrayList<>(lossEstimationParameters.getV1().size());
		for (int numServers : lossEstimationParameters.getV1()) {
			pLoss.add(estimateLoss(numServers, lossEstimationParameters.getV2(), baseExclusionSet));
		}
		return pLoss;
	}
	
	private List<Double> estimateAndDisplayLoss(Pair<List<Integer>,Integer> lossEstimationParameters, ExclusionSet baseExclusionSet) {
		List<Double>	pLoss;
		
		System.out.printf("Estimating loss\n");
		pLoss = estimateLoss(lossEstimationParameters, baseExclusionSet);
		System.out.printf("Loss estimation complete\n");
		System.out.printf("\nServers\tpLoss\n");
		for (int i = 0; i < lossEstimationParameters.getV1().size(); i++) {
			System.out.printf("%d\t%f\n", lossEstimationParameters.getV1().get(i), pLoss.get(i));
		}
		return pLoss;
	}
	
	private static double[] getNumServerLossProbabilities(double pServerLoss, int numServers, int maxLostServers) {
		double[]	p;
		
		System.out.println();
		p = new double[maxLostServers + 1];
		for (int i = 0; i <= maxLostServers; i++) {
			p[i] = (double)NumUtil.combinations(numServers, i) * Math.pow(1.0 - pServerLoss, numServers - i) * Math.pow(pServerLoss, i);
			System.out.printf("%d\t%f\n", i, p[i]);
		}
		return p;
	}
	
	private double[] estimateAndDisplayServerLoss(Pair<List<Integer>,Integer> lossEstimationParameters, double pServerLoss, List<Double> pLoss) {
		int			maxLostServers;
		double[]	serverLossProbabilities;
		
		maxLostServers = lossEstimationParameters.getV1().get(lossEstimationParameters.getV1().size() - 1);
		serverLossProbabilities = getNumServerLossProbabilities(pServerLoss, rMap.allReplicas().size(), maxLostServers);
		return null;
	}
	
	public static void main(String[] args) {
		try {
			SKGridConfiguration				gc;
			RingIntegrityCheck				ringIntegrityCheck;
			ExclusionSet					exclusionSet;
			RingIntegrityCheckOptions		options;
			CmdLineParser	        		parser;
			ClientDHTConfigurationProvider	configProvider;
			Triple<String, Long, Long>		ringAndVersionPair;
			Pair<List<Integer>,Integer>		lossEstimationParameters;
			List<Double>					pLoss;
			
			options = new RingIntegrityCheckOptions();
			parser = new CmdLineParser(options);
			try {
				if (options.exclusionSet != null && options.exclusionSetFile != null) {
					throw new CmdLineException("Only one of -s and -f may be specified");
				}
				parser.parseArgument(args);
			} catch (CmdLineException cle) {
				System.err.println(cle.getMessage());
				parser.printUsage(System.err);
				System.exit(-1);
			}
			
			if (options.gridConfig == null) {
				options.gridConfig = GridConfiguration.getDefaultGC();
			}
			
    		if (options.gridConfigBase != null) {
    			gc = SKGridConfiguration.parseFile(new File(options.gridConfigBase), options.gridConfig);
    		} else {
    			gc = SKGridConfiguration.parseFile(options.gridConfig);
    		}
			
    		if (options.ringAndVersionPair != null) {
    			String[]	toks;
    			
    			toks = options.ringAndVersionPair.split(",");
    			ringAndVersionPair = new Triple<>(toks[0], Long.parseLong(toks[1]), Long.parseLong(toks[2]));
    		} else {
    			ringAndVersionPair = null;
    		}
    		
    		ringIntegrityCheck = new RingIntegrityCheck(gc, ringAndVersionPair);
    		if (options.exclusionSet != null) {
				exclusionSet = ExclusionSet.parse(options.exclusionSet);
    		} else if (options.exclusionSetFile != null) {
				exclusionSet = ExclusionSet.parse(new File(options.exclusionSetFile));
    		} else {
				exclusionSet = ringIntegrityCheck.getCurrentExclusionSet();
    		}
    		
			ringIntegrityCheck.checkIntegrity(exclusionSet, false);
			
			lossEstimationParameters = options.getLossEstimationParameters();
			pLoss = ringIntegrityCheck.estimateAndDisplayLoss(lossEstimationParameters, exclusionSet);
			
			if (options.serverFailureProbability != 0.0) {
				ringIntegrityCheck.estimateAndDisplayServerLoss(lossEstimationParameters, options.serverFailureProbability, pLoss);
			}
			
			System.exit(0);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}