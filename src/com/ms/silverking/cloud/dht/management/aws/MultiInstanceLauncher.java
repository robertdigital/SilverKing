package com.ms.silverking.cloud.dht.management.aws;

import static com.ms.silverking.cloud.dht.management.aws.Util.debugPrint;
import static com.ms.silverking.cloud.dht.management.aws.Util.deleteKeyPair;
import static com.ms.silverking.cloud.dht.management.aws.Util.ec2Client;
import static com.ms.silverking.cloud.dht.management.aws.Util.getInstanceIds;
import static com.ms.silverking.cloud.dht.management.aws.Util.getIps;
import static com.ms.silverking.cloud.dht.management.aws.Util.isRunning;
import static com.ms.silverking.cloud.dht.management.aws.Util.newKeyName;
import static com.ms.silverking.cloud.dht.management.aws.Util.newLine;
import static com.ms.silverking.cloud.dht.management.aws.Util.print;
import static com.ms.silverking.cloud.dht.management.aws.Util.printDone;
import static com.ms.silverking.cloud.dht.management.aws.Util.printInstance;
import static com.ms.silverking.cloud.dht.management.aws.Util.printNoDot;
import static com.ms.silverking.cloud.dht.management.aws.Util.userHome;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.InstanceStatusDetails;
import com.amazonaws.services.ec2.model.InstanceStatusSummary;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.UserIdGroupPair;
import com.ms.silverking.cloud.dht.management.SKCloudAdmin;

public class MultiInstanceLauncher {

	private final String masterIp;
	private final AmazonEC2 ec2;
	private String amiId;
	private String instanceType;
	private final boolean includeMaster;
	private int numWorkerInstances;
	
	private Instance launchInstance;
	private String keyPairName;
	private List<GroupIdentifier> securityGroups;
	private String subnetId;

	public static final String privateKeyFilename = userHome + "/.ssh/id_rsa";
	       static final String ipsFilename        = SKCloudAdmin.cloudOutDir + "/cloud_ip_list.txt";
	private String privateKey;
	
	private static final String newSecurityGroupName = "sk_instance";
	
	private List<Instance> workerInstances;
	
	private long lastMinutePrinted;
	
	public MultiInstanceLauncher(String masterIp, AmazonEC2 ec2, int numInstances, String amiId, String instanceType, boolean includeMaster) {
		this.masterIp      = masterIp;
		this.ec2           = ec2;
		this.amiId         = amiId;
		this.instanceType  = instanceType;
		this.includeMaster = includeMaster;
		
		Util.checkNumInstances(numInstances);
		setNumWorkerInstances(numInstances);
		
		workerInstances = new ArrayList<>();
		lastMinutePrinted = 0;
	}
	
	private void setNumWorkerInstances(int numInstances) {
		numWorkerInstances = numInstances;
		if (includeMaster)
			numWorkerInstances--;
	}
	
	public void run() {
		System.out.println("Creating " + numWorkerInstances + " new instance(s)");
		
		if (!isMasterOnlyInstance()) {
			setLaunchInstance();
	//		createSecurityGroup();
	//		addSecurityGroupToLaunchInstance();
		}
		
		deleteKeyPair(ec2);
		createKeyPair();
		createPrivateKeyFile();
		
		if (!isMasterOnlyInstance()) {
			createAndRunNewInstances();
			waitForInstancesToBeRunning();
			waitForInstancesToBeReachable();
		}
	
		createIpListFile();
	}
	
	boolean isMasterOnlyInstance() {
		return 0 == numWorkerInstances;
	}
	
	private void createSecurityGroup() {
//		DescribeSecurityGroupsResult dsgResult = ec2.describeSecurityGroups();
//		debugPrint(dsgResult);
		
		CreateSecurityGroupRequest sgRequest = new CreateSecurityGroupRequest();
		sgRequest.withGroupName(newSecurityGroupName)
				 .withDescription("For running sk instance(s)");
		CreateSecurityGroupResult createSecurityGroupResult = ec2.createSecurityGroup(sgRequest);

		IpPermission ipPermission = new IpPermission();
		UserIdGroupPair pair = new UserIdGroupPair();
		pair.withGroupName(newSecurityGroupName)	// or could have used .withGroupId(createSecurityGroupResult.getGroupId()) instead
			.withDescription("so machines can talk to each other");	
		ipPermission.withUserIdGroupPairs(Arrays.asList(pair))
		            .withIpProtocol("-1")
			        .withFromPort(-1)
			        .withToPort(-1);
		AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest = new AuthorizeSecurityGroupIngressRequest();
		authorizeSecurityGroupIngressRequest.withGroupName(newSecurityGroupName)
		                                    .withIpPermissions(ipPermission);
			
		ec2.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
	}
	
	private void setLaunchInstance() {
		print("Setting Launch Host");
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		while (true) {
		    DescribeInstancesResult response = ec2.describeInstances(request);

		    for (Reservation reservation : response.getReservations()) {
		        for (Instance instance : reservation.getInstances()) {
		            printInstance(instance);
			        if ( isLaunchInstance(instance) ) {
			        	setLaunchInstance(instance);
			        	return;
			        }
		        }
		    }

		    if (response.getNextToken() == null)
		        break;
		    
		    debugPrint("token: " + response.getNextToken());
		    request.setNextToken(response.getNextToken());
		}

		throw new RuntimeException("Couldn't find launch instance");
	}
	
	private boolean isLaunchInstance(Instance instance) {
		if (System.getProperty("os.name").toLowerCase().startsWith("windows"))
			return isRunning(instance) && instance.getImageId().equals("ami-68790210");
		else
			return isRunning(instance) && ipMatchesThisMachine(instance);				
	}
	
	private boolean ipMatchesThisMachine(Instance instance) {
		return instance.getPrivateIpAddress().equals(masterIp);
	}
	
	private void setLaunchInstance(Instance instance) {
		launchInstance = instance;
		
		if (isSet(amiId))
			amiId = "ami-"+amiId;
		else
			amiId = instance.getImageId();
		
		if (!isSet(instanceType))
			instanceType = instance.getInstanceType();
		
    	keyPairName    = instance.getKeyName();
    	securityGroups = instance.getSecurityGroups();
    	subnetId       = instance.getSubnetId();
    	printDetails();
    	printDone(instance.getInstanceId());
	}
	
	private boolean isSet(String value) {
		return value != null;
	}
	
	private void printDetails() {
		debugPrint("set launch instance: " + launchInstance);
		debugPrint("ami:    " + amiId);
		debugPrint("type:   " + instanceType);
		debugPrint("kp:     " + keyPairName);
		debugPrint("sg:     " + securityGroups);
		debugPrint("subnet: " + subnetId);
	}
	
	// we have to create a new keypair b/c the keypair we used to get access (ssh) to this master/launch instance, that key_pair is on our windows machine.. i.e. outside aws
	// this applies for masterOnlyInstances as well. w/o this, we can't even ssh to ourselves with masterOnlyInstances b/c of "Permission denied (publickey)" 
	private void createKeyPair() {
		print("Creating New Key Pair");
		
		CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest();
		createKeyPairRequest.withKeyName(newKeyName);

		CreateKeyPairResult createKeyPairResult = ec2.createKeyPair(createKeyPairRequest);
		
		KeyPair keyPair = createKeyPairResult.getKeyPair();
		privateKey = keyPair.getKeyMaterial();
		
		printDone(newKeyName);
	}
	
	private void createPrivateKeyFile() {
		print("Creating New Private Key File");
		
		writeToFile(privateKeyFilename, privateKey);
		
		printDone(privateKeyFilename);
	}
	
	private void writeToFile(String filename, String content) {
		File file = new File(filename);
		
	    try {
	    	// mkdirs and createNewFile only create iff those paths/file don't already exist. so no need to check for existence beforehand.
	    	file.getParentFile().mkdirs();
			file.createNewFile();
			FileWriter writer = new FileWriter(file);
			writer.write(content);
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

	    // needs to be done in this order, b/c "everyone else" wipes out all permissions (including "owner")...
	    // "everyone else"
		file.setExecutable(false, false);
		file.setReadable(  false, false);
		file.setWritable(  false, false);

		// "owner"
		file.setExecutable(false);
		file.setReadable(true);
		file.setWritable(true);
		
//	    System.out.println("Is Execute allow : " + file.canExecute());
//		System.out.println("Is Write allow : " +   file.canWrite());
//		System.out.println("Is Read allow : " +    file.canRead());
	}
    
	private void createAndRunNewInstances() {
		print("Creating New Instances");
		
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
		runInstancesRequest.withImageId(amiId)
		                   .withInstanceType(instanceType)
		                   .withMinCount(1)
		                   .withMaxCount(numWorkerInstances)
		                   .withKeyName(newKeyName)
		                   .withSecurityGroupIds( getSecurityGroupIds(securityGroups) )	// for some reason this one works and below doesn't
//		                   .withSecurityGroups( getNames(securityGroups) )	if you try to use .withSecurityGroups AND .withSubnetId, you will get Exception in thread "main" com.amazonaws.services.ec2.model.AmazonEC2Exception: The parameter groupName cannot be used with the parameter subnet (Service: AmazonEC2; Status Code: 400; Error Code: InvalidParameterCombination; Request ID: a230cc97-c84b-4253-bdf0-874c68759efd)
		                   .withSubnetId(subnetId);
				
		RunInstancesResult result = ec2.runInstances(runInstancesRequest);
		workerInstances = result.getReservation().getInstances();
		
		printDone( String.join(", ", getInstanceIds(workerInstances)) );
	}
	
	private void waitForInstancesToBeRunning() {
		print("  Waiting for Instances to be running");
		
		DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
		diRequest.withInstanceIds( getInstanceIds(workerInstances) );

		long sleepSeconds        = 5;
		long totalRunTimeSeconds = 2 * 60; 	
		int retriesCount = 0;
		List<String> ips = getIps(workerInstances);
		while (!ips.isEmpty()) {
		    DescribeInstancesResult response = ec2.describeInstances(diRequest);
		    for (Reservation reservation : response.getReservations()) {
				for (Instance instance : reservation.getInstances()) { 
//					System.out.println(instance.getState().getName());
					if (isRunning(instance)) {
//						System.out.println(instance.getPrivateIpAddress());
						ips.remove(instance.getPrivateIpAddress());
					}
				}
		    }

		    if (ips.isEmpty())
		    	break;
		    
		    checkForTimeoutException(sleepSeconds, totalRunTimeSeconds, retriesCount, "running");
		    
		    sleep(sleepSeconds);
		    retriesCount++;
		    printMinutesElapsed(sleepSeconds, retriesCount);
		}

		lastMinutePrinted = 0;
		printDone(String.join(", ", getIps(workerInstances)));
	}
	
	private void waitForInstancesToBeReachable() {
		printNoDot("  Waiting for Instances to be reachable");
		printNoDot("    be patient, it could take mins");

		DescribeInstanceStatusRequest disRequest = new DescribeInstanceStatusRequest();
		disRequest.withInstanceIds( getInstanceIds(workerInstances) );

		long sleepSeconds        = 15;
		long totalRunTimeSeconds = 10 * 60; 	
		int retriesCount = 0;
		List<String> ips = getIps(workerInstances);
		while (!ips.isEmpty()) {
		    DescribeInstanceStatusResult response = ec2.describeInstanceStatus(disRequest);
		    for (InstanceStatus status : response.getInstanceStatuses()) {
		    	if (passedSystemStatusCheck(status.getSystemStatus()) && passedInstanceStatusCheck(status.getInstanceStatus())) {
		    		Instance instance = getInstance(status.getInstanceId(), workerInstances);
		    		String ip = instance.getPrivateIpAddress();
		    		if (ips.contains(ip)) {	// avoids multiple printing of those that are already 'good', while waiting for the rest to reach 'good' status
						System.out.printf("\t%-17s is good%n", ip);
						ips.remove(ip);
		    		}	
		    	}
		    }

		    if (ips.isEmpty())
		    	break;
		    
		    checkForTimeoutException(sleepSeconds, totalRunTimeSeconds, retriesCount, "reachable");
		    		    
		    sleep(sleepSeconds);
		    retriesCount++;
		    printMinutesElapsed(sleepSeconds, retriesCount);
		}

		lastMinutePrinted = 0;
		print("");
		printDone(String.join(", ", getIps(workerInstances)));
	}
	
	private void checkForTimeoutException(long sleepSeconds, long totalRunTimeSeconds, int retriesCount, String status) {
		if (retriesCount*sleepSeconds > totalRunTimeSeconds)
	    	throwTimeoutException(status);
	}
	
	private void throwTimeoutException(String status) {
    	throw new RuntimeException("instances should have been " + status + " by now...");
	}
	
	private boolean passedSystemStatusCheck(InstanceStatusSummary statusSummary) {
		return isReachableAndStatusIsOk(statusSummary);
	}
	
	private boolean passedInstanceStatusCheck(InstanceStatusSummary statusSummary) {
		return isReachableAndStatusIsOk(statusSummary);
	}
	
	private boolean isReachableAndStatusIsOk(InstanceStatusSummary statusSummary) {
		return reachabilityPassed(statusSummary.getDetails()) && statusIsOk(statusSummary.getStatus());
	}
	
	private boolean reachabilityPassed(List<InstanceStatusDetails> details) {
		for (InstanceStatusDetails detail : details)
			if (detail.getName().equals("reachability") && detail.getStatus().equals("passed"))
				return true;

		return false;
	}
	
	private boolean statusIsOk(String status) {
		return status.equals("ok");
	}
	
	private void sleep(long seconds) {
	    try {
			Thread.sleep(seconds*1_000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private void printMinutesElapsed(long sleepSeconds, int retries) {
		long minute = sleepSeconds*retries / 60;
		if (minute != 0 && minute != lastMinutePrinted) {
			System.out.println("      *" + minute + " mins elapsed*");
			lastMinutePrinted = minute;
		}
	}
	
	private Instance getInstance(String id, List<Instance> instances) {
		for (Instance instance : instances)
			if (instance.getInstanceId().equals(id))
				return instance;
		
		throw new RuntimeException("instance '"+id+"' not found");
	}
	
	private List<String> getSecurityGroupIds(List<GroupIdentifier> securityGroups) {
		List<String> names = new ArrayList<>();
		
		for (GroupIdentifier group : securityGroups)
			names.add(group.getGroupId());
		
		return names;
	}
	
//	private List<String> getNames(List<GroupIdentifier> securityGroups) {
//		List<String> names = new ArrayList<>();
//		
//		for (GroupIdentifier group : securityGroups)
//			names.add(group.getGroupName());
//		
//		return names;
//	}
	
	private void createIpListFile() {
		print("Creating IpList File");
		
		writeToFile(ipsFilename, String.join(newLine, getInstanceIps()) + newLine);
		
		printDone(ipsFilename);
	}
	
	public List<String> getWorkerIps() {
		return getIps(workerInstances);
	}
	
	public List<String> getInstanceIps() {
		if (isMasterOnlyInstance())
			return Arrays.asList(masterIp);
		else {
			List<String> instanceIps = getWorkerIps();
			if (includeMaster)
				instanceIps.add(0, masterIp);
			
			return instanceIps;
		}
	}
	
    public static void main(String[] args) throws Exception {
        if (args.length == 0)
        	throw new RuntimeException("We need to know how many instances to start. Please pass in <numberOfInstances>");
        
        int numInstances = Integer.valueOf(args[0]);        
        System.out.println("Attempting to launch " + (numInstances-1) + " new instances, for a total of " + numInstances + " (this instance + those " + (numInstances-1) + ")");
    	String masterIp = InetAddress.getLocalHost().getHostAddress();
        MultiInstanceLauncher launcher = new MultiInstanceLauncher(masterIp, ec2Client, numInstances, null, null, true);
        launcher.run();
	}

}
