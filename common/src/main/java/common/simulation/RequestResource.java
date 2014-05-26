package common.simulation;

import se.sics.kompics.Event;

public final class RequestResource extends Event {
    
    private final long id;
    private final int numCpus;
    private final int memoryInMbs;
    private final int timeToHoldResource;
    private  long startTime;
    private  long enqueueTime;
    private  long dequeueTime;

    public RequestResource(long id, int numCpus, int memoryInMbs, int timeToHoldResource) {
        this.id = id;
        this.numCpus = numCpus;
        this.memoryInMbs = memoryInMbs;
        this.timeToHoldResource = timeToHoldResource;
        this.startTime=-1;
        this.enqueueTime=-1;
        this.dequeueTime=-1;
    }
    
    public long getStartTime() {
		return startTime;
	}


	public long getEnqueueTime() {
		return enqueueTime;
	}


	public long getDequeueTime() {
		return dequeueTime;
	}


	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}


	public void setEnqueueTime(long enqueueTime) {
		this.enqueueTime = enqueueTime;
	}


	public void setDequeueTime(long dequeueTime) {
		this.dequeueTime = dequeueTime;
	}

    public long getId() {
        return id;
    }

    public int getTimeToHoldResource() {
        return timeToHoldResource;
    }

    public int getMemoryInMbs() {
        return memoryInMbs;
    }

    public int getNumCpus() {
        return numCpus;
    }
    
}
