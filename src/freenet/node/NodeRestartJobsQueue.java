package freenet.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.client.async.DBJob;
import freenet.support.Logger;

public class NodeRestartJobsQueue {
	
	private final long nodeDBHandle;

	public NodeRestartJobsQueue(long nodeDBHandle2) {
		nodeDBHandle = nodeDBHandle2;
		dbJobs = new Set[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		for(int i=0;i<dbJobs.length;i++)
			dbJobs[i] = new HashSet<DBJob>();
	}

	public static NodeRestartJobsQueue init(final long nodeDBHandle, ObjectContainer container) {
		ObjectSet<NodeRestartJobsQueue> results = 
			container.query(new Predicate<NodeRestartJobsQueue>() {

			@Override
			public boolean match(NodeRestartJobsQueue arg0) {
				return (arg0.nodeDBHandle == nodeDBHandle);
			}
			
		});
		if(results.hasNext()) {
			NodeRestartJobsQueue queue = (NodeRestartJobsQueue) results.next();
			container.activate(queue, 1);
			queue.onInit(container);
			return queue;
		}
		NodeRestartJobsQueue queue = new NodeRestartJobsQueue(nodeDBHandle);
		container.store(queue);
		return queue;
	}

	private void onInit(ObjectContainer container) {
		// FIXME do something, maybe activate?
	}

	private final Set<DBJob>[] dbJobs;
	
	public void queueRestartJob(DBJob job, int priority, ObjectContainer container) {
		container.activate(dbJobs[priority], 1);
		dbJobs[priority].add(job);
		container.store(dbJobs[priority]);
		container.deactivate(dbJobs[priority], 1);
	}
	
	public void removeRestartJob(DBJob job, int priority, ObjectContainer container) {
		boolean jobWasActive = container.ext().isActive(job);
		if(!jobWasActive) container.activate(job, 1);
		container.activate(dbJobs[priority], 1);
		if(!dbJobs[priority].remove(job)) {
			int found = 0;
			for(int i=0;i<dbJobs.length;i++) {
				container.activate(dbJobs[priority], 1);
				if(dbJobs[priority].remove(job)) {
					container.store(dbJobs[priority]);
					found++;
				}
				container.deactivate(dbJobs[priority], 1);
			}
			if(found > 0)
				Logger.error(this, "Job "+job+" not in specified priority "+priority+" found in "+found+" other priorities when removing");
			else
				Logger.error(this, "Job "+job+" not found when removing it");
		} else {
			container.store(dbJobs[priority]);
			container.deactivate(dbJobs[priority], 1);
		}
		if(!jobWasActive) container.deactivate(job, 1);
	}
	
	DBJob[] getRestartDatabaseJobs(ObjectContainer container) {
		ArrayList<DBJob> list = new ArrayList<DBJob>();
		for(int i=0;i<dbJobs.length;i++) {
			container.activate(dbJobs[i], 1);
			list.addAll(dbJobs[i]);
			dbJobs[i].clear();
			container.store(dbJobs[i]);
			container.deactivate(dbJobs[i], 1);
		}
		return list.toArray(new DBJob[list.size()]);
	}
	
}