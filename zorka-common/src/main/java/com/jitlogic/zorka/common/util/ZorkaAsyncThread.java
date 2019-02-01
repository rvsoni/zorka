/*
 * Copyright 2012-2019 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 * <p/>
 * This is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p/>
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */
package com.jitlogic.zorka.common.util;

import com.jitlogic.zorka.common.ZorkaService;
import com.jitlogic.zorka.common.ZorkaSubmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements asunchronous processing thread with submit queue.
 *
 * @param <T> type of elements in a queue
 *            <p/>
 *            TODO factor out direct processing functionality (exposing process(), flush(), open(), close() etc.)
 */
public abstract class ZorkaAsyncThread<T> implements Runnable, ZorkaService, ZorkaSubmitter<T> {

    /** Logger */
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    /** Queue length */
    protected int qlen;

    /** Submit queue */
    protected BlockingQueue<T> submitQueue;

    /** Thred name (will be prefixed with ZORKA-) */
    private final String name;

    /** Processing thread will be working as long as this attribute value is true */
    private final AtomicBoolean running = new AtomicBoolean(false); // TODO use volatile here

    /** Thread object representing actual processing thread. */
    private Thread thread;

    protected boolean countTraps = true;

    /** Maximum number of items taken from queue at once. */
    private int plen;
    
    /** Sleeping interval in milliseconds */
    private long interval = 0l ;

    public ZorkaAsyncThread(String name) {
        this(name, 256, 2);
    }

    /**
     * Standard constructor.
     *
     * @param name thread name
     * @param plen
     */
    public ZorkaAsyncThread(String name, int qlen, int plen) {
        this.name = "ZORKA-" + name;
        this.plen = plen;
        this.qlen = qlen;
        if (qlen > 0) {
            submitQueue = new ArrayBlockingQueue<T>(qlen);
        }
    }
    
    /**
     * Constructor with interval
     *
     * @param name thread name
     * @param plen
     * @param interval in seconds
     */
    public ZorkaAsyncThread(String name, int qlen, int plen, int interval) {
    	this(name, qlen, plen);
    	
    	// convert to millis
        this.interval = interval * 1000L;
    }
    

    /**
     * This method starts thread.
     */
    public void start() {
        synchronized (this) {
            if (thread == null) {
                try {
                    open();
                    if (qlen > 0) {
                        thread = new Thread(this);
                        thread.setName(name);
                        thread.setDaemon(true);
                        running.set(true);
                        thread.start();
                    }
                } catch (Exception e) {
                    handleError("Error starting thread", e);
                }
            }
        }
    }

    /**
     * This method causes thread to stop (soon).
     */
    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        init();
        while (running.get()) {
            runCycle();

            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                log.error("Interrupted sleeping", e);
            }
        }

        synchronized (this) {
            close();
            thread = null;
        }
    }

    /**
     * Processes single item from submit queue (if any).
     */
    public void runCycle() {
        try {
            T obj = submitQueue.take();
            if (obj != null) {
                List<T> lst = new ArrayList<T>(plen);
                lst.add(obj);
                if (plen > 1) {
                    submitQueue.drainTo(lst, plen-1);
                }
                process(lst);
                flush();
            }
        } catch (InterruptedException e) {
            log.error("Cannot perform run cycle", e);
        }
    }

    /**
     * Submits object to a queue.
     *
     * @param obj object to be submitted
     */
    public boolean submit(T obj) {

        if (log.isTraceEnabled()) {
            log.trace("Submitting: " + obj);
        }

        try {
            if (qlen > 0) {
                return submitQueue.offer(obj, 1, TimeUnit.MILLISECONDS);
            } else {
                process(Collections.singletonList(obj));
                return true;
            }
        } catch (Exception e) {
            log.error("Error submitting item [" + name + "]", e);
            return false;
        }
    }


    protected abstract void process(List<T> obj);


    /**
     * Override this method if some resources have to be allocated
     * before thread starts (eg. network socket).
     */
    public void open() {

    }

    /**
     * Override this method if some resources need to be allocated
     * in the background thread before starting processing work.
     */
    protected void init() {

    }


    /**
     * Override this method if some resources have to be disposed
     * after thread stops (eg. network socket)
     */
    public void close() {

    }


    /**
     * Flushes unwritten data to disk if necessary.
     */
    protected void flush() {

    }

    public void shutdown() {
        close();
        stop();
    }

    /**
     * Error handling method - called when processing errors occur.
     *
     * @param message error message
     * @param e       exception object
     */
    protected void handleError(String message, Throwable e) {
        if (log != null) {
            log.error(message, e);
        }
    }

    public void disableTrapCounter() {
        countTraps = false;
    }

    public BlockingQueue<T> getSubmitQueue() {
        return submitQueue;
    }

    public int getQueueLength() {
        return qlen;
    }
}
