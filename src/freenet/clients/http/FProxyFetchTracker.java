package freenet.clients.http;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Vector;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class FProxyFetchTracker implements Runnable {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}
	
	final MultiValueTable<FreenetURI, FProxyFetchInProgress> fetchers;
	final ClientContext context;
	private long fetchIdentifiers;
	private final FetchContext fctx;
	private final RequestClient rc;
	private boolean queuedJob;
	private boolean requeue;

	FProxyFetchTracker(ClientContext context, FetchContext fctx, RequestClient rc) {
		fetchers = new MultiValueTable<FreenetURI, FProxyFetchInProgress>();
		this.context = context;
		this.fctx = fctx;
		this.rc = rc;
	}
	
	FProxyFetchWaiter makeFetcher(FreenetURI key, long maxSize) throws FetchException {
		FProxyFetchInProgress progress;
		/* LOCKING:
		 * Call getWaiter() inside the fetchers lock, since we will purge old 
		 * fetchers inside that lock, hence avoid a race condition. FetchInProgress 
		 * lock is always taken last. */
		synchronized(fetchers) {
			if(fetchers.containsKey(key)) {
				Object[] check = fetchers.getArray(key);
				for(int i=0;i<check.length;i++) {
					progress = (FProxyFetchInProgress) check[i];
					if((progress.maxSize == maxSize && progress.notFinishedOrFatallyFinished())
							|| progress.hasData()) return progress.getWaiter();
				}
			}
			progress = new FProxyFetchInProgress(this, key, maxSize, fetchIdentifiers++, context, fctx, rc);
			fetchers.put(key, progress);
		}
		try {
			progress.start(context);
		} catch (FetchException e) {
			synchronized(fetchers) {
				fetchers.removeElement(key, progress);
			}
			throw e;
		}
		if(logMINOR) Logger.minor(this, "Created new fetcher: "+progress);
		return progress.getWaiter();
		// FIXME promote a fetcher when it is re-used
		// FIXME get rid of fetchers over some age
	}

	public void queueCancel(FProxyFetchInProgress progress) {
		if(logMINOR) Logger.minor(this, "Queueing removal of old FProxyFetchInProgress's");
		synchronized(this) {
			if(queuedJob) {
				requeue = true;
				return;
			}
			queuedJob = true;
		}
		context.ticker.queueTimedJob(this, FProxyFetchInProgress.LIFETIME);
	}

	public void run() {
		if(logMINOR) Logger.minor(this, "Removing old FProxyFetchInProgress's");
		ArrayList<FProxyFetchInProgress> toRemove = null;
		boolean needRequeue = false;
		synchronized(fetchers) {
			if(requeue) {
				requeue = false;
				needRequeue = true;
			} else {
				queuedJob = false;
			}
			// Horrible hack, FIXME
			Enumeration e = fetchers.keys();
			while(e.hasMoreElements()) {
				FreenetURI uri = (FreenetURI) e.nextElement();
				// Really horrible hack, FIXME
				Vector<FProxyFetchInProgress> list = (Vector<FProxyFetchInProgress>) fetchers.iterateAll(uri);
				for(FProxyFetchInProgress f : list)
					// FIXME remove on the fly, although cancel must wait
					if(f.canCancel()) {
						if(toRemove == null) toRemove = new ArrayList<FProxyFetchInProgress>();
						toRemove.add(f);
					}
			}
			if(toRemove != null)
			for(FProxyFetchInProgress r : toRemove) {
				fetchers.removeElement(r.uri, r);
			}
		}
		if(toRemove != null)
		for(FProxyFetchInProgress r : toRemove)
			r.finishCancel();
		if(needRequeue)
			context.ticker.queueTimedJob(this, FProxyFetchInProgress.LIFETIME);
	}

}
