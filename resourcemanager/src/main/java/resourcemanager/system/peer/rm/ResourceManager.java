package resourcemanager.system.peer.rm;

import common.configuration.RmConfiguration;
import common.peer.AvailableResources;
import common.simulation.RequestResource;
import cyclon.system.peer.cyclon.CyclonSample;
import cyclon.system.peer.cyclon.CyclonSamplePort;
import cyclon.system.peer.cyclon.PeerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.address.Address;
import se.sics.kompics.network.Network;
import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.ScheduleTimeout;
import se.sics.kompics.timer.Timer;
import se.sics.kompics.web.Web;
import system.peer.RmPort;
import tman.system.peer.tman.TManSample;
import tman.system.peer.tman.TManSamplePort;

/**
 * resource scheduler
 *
 * @author jdowling
 * @update NinaZeng Mark
 */
public final class ResourceManager extends ComponentDefinition {

    private static final Logger logger = LoggerFactory.getLogger(ResourceManager.class);
    Positive<RmPort> indexPort = positive(RmPort.class); //this is the port to receive incoming RequestResources event
    Positive<Network> networkPort = positive(Network.class);
    Positive<Timer> timerPort = positive(Timer.class);
    Negative<Web> webPort = negative(Web.class);
    Positive<CyclonSamplePort> cyclonSamplePort = positive(CyclonSamplePort.class);
    Positive<TManSamplePort> tmanPort = positive(TManSamplePort.class);
    ArrayList<Address> neighbours = new ArrayList<Address>(); // this is the partial view of a node
    private Address self;
    private RmConfiguration configuration;
    Random random;
    private AvailableResources availableResources; // this is for Worker
    Comparator<PeerDescriptor> peerAgeComparator = new Comparator<PeerDescriptor>() {
        @Override
        public int compare(PeerDescriptor t, PeerDescriptor t1) {
            if (t.getAge() > t1.getAge()) {
                return 1;
            } else {
                return -1;
            }
        }
    };

    private static final int NUM_PROBES = 3;

    //This is for Scheduler. The parameter hold the jobs that will be assigned to Workers.
    private Map<Long, RequestResource> jobsFromSimulator = new HashMap<Long, RequestResource>();

    //This is for Worker. ResquestResource is Job. Queues Jobs that are assigned from Schedulers.
    private List<RequestResource> queuedJobs = new ArrayList<RequestResource>();

    //This is for Scheduler. It holds the probe response for one job.
    private Map<Long, List<RequestResources.Response>> receivedProbes = new HashMap<Long, List<RequestResources.Response>>();

    //statistical record of latency 
    private long startTime, endTime, averageTime;
    private static Map<Long, Long> timePerRequest = new HashMap<Long, Long>();

    public ResourceManager() {

        subscribe(handleInit, control);
        subscribe(handleCyclonSample, cyclonSamplePort);
        subscribe(handleRequestResource, indexPort);
        subscribe(handleUpdateTimeout, timerPort);
        subscribe(handleResourceAllocationRequest, networkPort);
        subscribe(handleResourceAllocationResponse, networkPort);
        subscribe(handleTManSample, tmanPort);
        subscribe(handleAssignJob, networkPort);
        subscribe(handleJobFinishTimeout, timerPort);
    }

    Handler<RmInit> handleInit = new Handler<RmInit>() {
        @Override
        public void handle(RmInit init) {
            self = init.getSelf();
            configuration = init.getConfiguration();
            random = new Random(init.getConfiguration().getSeed());
            long period = configuration.getPeriod();
            availableResources = init.getAvailableResources();
            SchedulePeriodicTimeout rst = new SchedulePeriodicTimeout(period, period);
            rst.setTimeoutEvent(new UpdateTimeout(rst));
            trigger(rst, timerPort);

        }
    };

//--------------------------------------------------------------------------------      
    /**
     * still don't know what this for
     */
    Handler<UpdateTimeout> handleUpdateTimeout = new Handler<UpdateTimeout>() {
        @Override
        public void handle(UpdateTimeout event) {

            // pick a random neighbour to ask for index updates from. 
            // You can change this policy if you want to.
            // Maybe a gradient neighbour who is closer to the leader?
            if (neighbours.isEmpty()) {
                return;
            }
            Address dest = neighbours.get(random.nextInt(neighbours.size()));

        }
    };

//--------------------------------------------------------------------------------
    /**
     * Handle incoming resource allocation request. 
     * Check if we have enough resources.
     * This is for Worker. It listens Request from the Scheduler which probe the Worker.
     */
    Handler<RequestResources.Request> handleResourceAllocationRequest = new Handler<RequestResources.Request>() {
        @Override
        public void handle(RequestResources.Request event) {
            //boolean isAvailabel = (availableResources.getFreeMemInMbs() >= event.getAmountMemInMb()) && (availableResources.getNumFreeCpus() >= event.getNumCpus());
            boolean isAvailabel = availableResources.isAvailable(event.getNumCpus(), event.getAmountMemInMb());
            trigger(new RequestResources.Response(self, event.getSource(), event.getJobID(), isAvailabel, queuedJobs.size()), networkPort);
        }
    };

//--------------------------------------------------------------------------------
    /**
     * Handle incoming resource allocation response. Did we get the requested
     * resources? This is for Scheduler. It listens to the Respond from the
     * Worker. The Respond is the probes that was sent before. If all the probes
     * returned, the decision will be made by the Scheduler. The decison is
     * decided according to the size of the job queue.
     */
    Handler<RequestResources.Response> handleResourceAllocationResponse = new Handler<RequestResources.Response>() {
        @Override
        public void handle(RequestResources.Response event) {
    //        logger.info(self.getId() + "收到probe的respond，这个job的id是：" + event.getJobID());

            List<RequestResources.Response> list = receivedProbes.get(event.getJobID());
            if (list == null) {
                list = new ArrayList<RequestResources.Response>();
                receivedProbes.put(event.getJobID(), list);
            }
            list.add(event);

            if (list.size() == NUM_PROBES) {
                RequestResources.Response minLoadResponse = Collections.min(list); // ? get the least loaded TBD
                Address selectedPeer = minLoadResponse.getSource();
                RequestResources.AssignJob schJob = new RequestResources.AssignJob(self, selectedPeer, jobsFromSimulator.get(event.getJobID()));
                trigger(schJob, networkPort);
            //    jobsFromSimulator.remove(event.getJobID());
            }
        }
    };
    
//--------------------------------------------------------------------------------
    Handler<CyclonSample> handleCyclonSample = new Handler<CyclonSample>() {
        @Override
        public void handle(CyclonSample event) {

            neighbours.clear();
            neighbours.addAll(event.getSample());


        }
    };

//--------------------------------------------------------------------------------
    /**
     * This is for Scheduler. RequestResource is from DataCenterSimulator. The
     * handler's function is: send probe to Workers.
     */
    Handler<RequestResource> handleRequestResource = new Handler<RequestResource>() {
        @Override
        public void handle(RequestResource event) {
  //          System.out.println("处理RequestResource的请求");
   // logger.info("Simulator分配的任务为：" + event.getNumCpus() + "个cup，" + event.getMemoryInMbs() + "M存储空间" + "Job ID:"+ event.getId() +"\n");

            
            List<Address> tempNeighbours = new ArrayList<Address>();
            tempNeighbours.addAll(neighbours);
            jobsFromSimulator.put(event.getId(), event); // Store the job in the map.
         //   logger.info("jobsFromSimulator"+ jobsFromSimulator.size()+"\n");
            int times = Math.min(NUM_PROBES, neighbours.size());
            for (int i = 0; i < times; i++) {
                int index = (int) Math.round(Math.random() * (tempNeighbours.size() - 1));
                RequestResources.Request req = new RequestResources.Request(self, tempNeighbours.get(index), event.getId(), event.getNumCpus(), event.getMemoryInMbs());
                tempNeighbours.remove(index);
            //    event.setStartTime(System.currentTimeMillis());
            //    startTime = System.currentTimeMillis();
                timePerRequest.put(event.getId(), System.currentTimeMillis());
                trigger(req, networkPort);
            }

        }
    };

//--------------------------------------------------------------------------------
    /**
     * This is for Worker. Put the job in the queue if there is not enough
     * resources. Allocate the resources if there is enough resources.
     */
    Handler<RequestResources.AssignJob> handleAssignJob = new Handler<RequestResources.AssignJob>() {
        @Override
        public void handle(RequestResources.AssignJob event) {
            RequestResource job = event.getJob();
            if(queuedJobs.size()>0){
            	queuedJobs.add(job);
            	logger.info("*******************Job ID Enqueue:" + job.getId());
          // 	jobsFromSimulator.get(job.getId()).setEnqueueTime(System.currentTimeMillis());
            	
            }
            else 
            if (availableResources.isAvailable(job.getNumCpus(), job.getMemoryInMbs())) {
                availableResources.allocate(job.getNumCpus(), job.getMemoryInMbs());
        //        logger.info("Job ID dequeue:" + job.getId());
       
                endTime = System.currentTimeMillis();
				startTime = timePerRequest.get(job.getId());
				timePerRequest.put(job.getId(), (endTime - startTime));
				averageTime = getAverageTime();		
				
				
            //    jobsFromSimulator.get(job.getId()).setDequeueTime(System.currentTimeMillis());
            //    jobsFromSimulator.remove(job.getId());
            } 
            else {
                queuedJobs.add(job);
                logger.info("*******************Job ID Enqueue:" + job.getId());
           //     jobsFromSimulator.get(event.getJob().getId()).setEnqueueTime(System.currentTimeMillis());
            }
  
            ScheduleTimeout st = new ScheduleTimeout(job.getTimeToHoldResource());
            st.setTimeoutEvent(new JobFinishTimeout(st, job.getId()));
            trigger(st, timerPort);
        }

    };
    
//--------------------------------------------------------------------------------
    /**
     * This is for Worker.
     * It listens to the JobFinishTimeout event.
     * It indicate that the job has been executed successfully
     */
    Handler<JobFinishTimeout> handleJobFinishTimeout = new Handler<JobFinishTimeout>() {

        @Override
        public void handle(JobFinishTimeout event) {
            for(int i=0; i<queuedJobs.size(); i++) {
                if(queuedJobs.get(i).getId() == event.getJobID()) {
                    availableResources.release(queuedJobs.get(i).getNumCpus(), queuedJobs.get(i).getMemoryInMbs());
                    queuedJobs.remove(queuedJobs.get(i));
                    break;
                }
            }
            
            // handle the next queued job in the waiting list. FIFO
            if (!queuedJobs.isEmpty()) {

				RequestResource nextjob = queuedJobs.get(0);
			//	logger.info("job finished"+"Worker ID"+self.getId()+"will arrange resource for"+"FIFO queue next job :" + queuedJobs.get(0).getId());

				boolean success = availableResources.isAvailable(
						nextjob.getNumCpus(), nextjob.getMemoryInMbs());
				if (success) {
					availableResources.allocate(nextjob.getNumCpus(),
							nextjob.getMemoryInMbs());
				//	jobsFromSimulator.get(nextjob.getId()).setDequeueTime(System.currentTimeMillis());
				//	jobsFromSimulator.remove(nextjob.getId());
					endTime = System.currentTimeMillis();
					startTime = timePerRequest.get(nextjob.getId());
					timePerRequest.put(nextjob.getId(), (endTime - startTime));
					averageTime = getAverageTime();					
					
					ScheduleTimeout st = new ScheduleTimeout(nextjob.getTimeToHoldResource());
					st.setTimeoutEvent(new JobFinishTimeout(st, nextjob.getId()));
					trigger(st, timerPort);
				}
			}
            
        }
    };
    
    static long getAverageTime() {
    	logger.info("how many jobs:"+timePerRequest.size()+"\n ");
    	logger.info("timeperRequest"+ timePerRequest);
		long sum = 0;
		for(Long l : timePerRequest.values()) {
			sum += l;
		}
		if(timePerRequest.size()==0){
			return 0;
		}else{
		logger.info("avarage latency:"+ sum / timePerRequest.size());
		return sum / timePerRequest.size();		
		}
	}

//--------------------------------------------------------------------------------
    Handler<TManSample> handleTManSample = new Handler<TManSample>() {
        @Override
        public void handle(TManSample event) {
            // TODO: 
        }
    };

}
