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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */

package com.jitlogic.zorka.core.spy;

import com.jitlogic.zorka.common.stats.MethodCallStatistic;
import com.jitlogic.zorka.common.stats.MethodCallStatistics;
import com.jitlogic.zorka.common.tracedata.SymbolRegistry;
import com.jitlogic.zorka.common.util.ZorkaConfig;
import com.jitlogic.zorka.core.ZorkaBshAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * This is main class transformer installed in JVM by Zorka agent (see premain() method).
 *
 * @author rafal.lewczuk@jitlogic.com
 */
public class SpyClassTransformer implements ClassFileTransformer {

    /**
     * Logger
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * All spy defs configured
     */
    private Map<String, SpyDefinition> sdefs = new LinkedHashMap<String, SpyDefinition>();


    /**
     * SpyContext counter.
     */
    private int nextId = 1;

    /**
     * Map of spy contexts (by ID)
     */
    private Map<Integer, SpyContext> ctxById = new ConcurrentHashMap<Integer, SpyContext>();

    /**
     * Map of spy contexts (by instance)
     */
    private Map<SpyContext, SpyContext> ctxInstances = new HashMap<SpyContext, SpyContext>();

    private ThreadLocal<Boolean> transformLock = new ThreadLocal<Boolean>();

    private ThreadLocal<Set<String>> currentTransformsTL = new ThreadLocal<Set<String>>() {
        @Override
        public Set<String> initialValue() {
            return new HashSet<String>();
        }
    };

    private SpyClassResolver resolver;

    private SymbolRegistry symbolRegistry;

    private SpyRetransformer retransformer;

    private boolean computeFrames;

    private boolean expandedFrames;

    private boolean scriptsAuto;

    private ZorkaBshAgent bshAgent;

    /**
     * Reference to tracer instance.
     */
    private Tracer tracer;

    private boolean useCustomResolver = true;

    private MethodCallStatistic tracerLookups, classesProcessed, classesTransformed, spyLookups, nullsEncountered;

    private boolean dumpEnabled = false;
    private List<Pattern> dumpFilters = new ArrayList<Pattern>();
    private File dumpDir;

    /**
     * Creates new spy class transformer
     *
     * @param tracer reference to tracer engine object
     */
    public SpyClassTransformer(SymbolRegistry symbolRegistry, Tracer tracer, ZorkaBshAgent bshAgent, ZorkaConfig config,
                               MethodCallStatistics statistics, SpyRetransformer retransformer) {
        this.symbolRegistry = symbolRegistry;
        this.tracer = tracer;
        this.bshAgent = bshAgent;
        this.computeFrames = config.boolCfg("zorka.spy.compute.frames", true);
        this.useCustomResolver = config.boolCfg("zorka.spy.custom.resolver", true);
        this.scriptsAuto = config.boolCfg("scripts.auto", true);
        this.retransformer = retransformer;
        this.expandedFrames = config.boolCfg("zorka.spy.expanded.frames", false);

        if (useCustomResolver) {
            this.resolver = new SpyClassResolver(statistics);
        }

        this.spyLookups = statistics.getMethodCallStatistic("SpyLookups");
        this.tracerLookups = statistics.getMethodCallStatistic("TracerLookups");
        this.classesProcessed = statistics.getMethodCallStatistic("ClassesProcessed");
        this.nullsEncountered = statistics.getMethodCallStatistic("SpyNullsEncountered");
        this.classesTransformed = statistics.getMethodCallStatistic("ClassesTransformed");

        List<String> df = config.listCfg("spy.dump");
        if (df.size() > 0) {
            dumpEnabled = true;
            log.info("Enabling bytecode dump for: " + df);
            for (String d : df) {
                dumpFilters.add(Pattern.compile(d
                        .replace(".", "\\.")
                        .replace("**", ".+")
                        .replace("*", "[^\\.]*")));
            }
            dumpDir = new File(config.getHomeDir(), "dump");
        }

        if (!computeFrames) {
            log.info("Disabling COMPUTE_FRAMES. Remeber to add -XX:-UseSplitVerifier JVM option in JDK7 or -noverify in JDK8.");
        }
    }


    /**
     * Returns context by its ID
     */
    public SpyContext getContext(int id) {
        return ctxById.get(id);
    }


    /**
     * Looks up for a spy context with the same configuration. If there is one, it will be returned.
     * If there is none, supplied context will be registered and will have an  ID assigned.
     *
     * @param keyCtx sample (possibly unregistered) context
     * @return registered context
     *         <p/>
     *         TODO get rid of this crap, use strings to find already created contexts for a method
     *         TODO BUG one context ID refers only to one sdef, so using multiple sdefs on a single method will result errors (submitting data from all probes only to first one)
     */
    public SpyContext lookup(SpyContext keyCtx) {
        synchronized (this) { // TODO get rid of synchronized, use
            SpyContext ctx = ctxInstances.get(keyCtx);
            if (ctx == null) {
                ctx = keyCtx;
                ctx.setId(nextId++);
                ctxInstances.put(ctx, ctx);
                ctxById.put(ctx.getId(), ctx);
                log.info("NEW: SpyContext: id=" + ctx.getId() + ", name=" + ctx.getSpyDefinition().getName() + ", code="
                    + ctx.getClassName() + "." + ctx.getMethodName() + "()");
            }
            return ctx;
        }
    }


    /**
     * Adds sdef configuration to transformer. For all subsequent classes pushed through transformer,
     * it will look if this sdef matches and possibly instrument methods according to sdef.
     *
     * @param sdef spy definition
     * @return
     */
    public synchronized SpyDefinition add(SpyDefinition sdef) {
        SpyDefinition osdef = sdefs.get(sdef.getName());

        log.info((osdef == null ? "Adding " : "Replacing ") + sdef.getName() + " spy definition.");

        boolean shouldRetransform = osdef != null && !osdef.sameProbes(sdef) && !retransformer.isEnabled();

        if (shouldRetransform) {
            log.warn("Cannot overwrite spy definition '" + osdef.getName()
                    + "' because probes have changed and retransform is not possible.");
            return null;
        }

        sdefs.put(sdef.getName(), sdef);

        if (retransformer.isEnabled() && (osdef == null || !osdef.sameProbes(sdef))) {
            retransformer.retransform(osdef != null ? osdef.getMatcherSet() : null, sdef.getMatcherSet(), true);
        } else {
            log.info("Probes didn't change for " + sdef.getName() + ". Retransform not needed.");
        }

        if (osdef != null) {
            for (Map.Entry<SpyContext, SpyContext> e : ctxInstances.entrySet()) {
                SpyContext ctx = e.getValue();
                if (ctx.getSpyDefinition() == osdef) {
                    ctx.setSpyDefinition(sdef);
                }
            }
        }

        return sdef;
    }


    public synchronized void remove(String sdefName) {
        SpyDefinition sdef = sdefs.get(sdefName);
        if (sdef != null) {
            remove(sdef);
        }
    }


    public synchronized void remove(SpyDefinition sdef) {
        if (sdefs.get(sdef.getName()) == sdef) {
            log.info("Removing spy definition: " + sdef.getName());

            sdefs.remove(sdef.getName());

            Set<SpyContext> ctxs = new HashSet<SpyContext>();
            Set<Integer> ids = new HashSet<Integer>();

            for (Map.Entry<Integer, SpyContext> e : ctxById.entrySet()) {
                ids.add(e.getKey());
                ctxs.add(e.getValue());
            }

            for (Integer id : ids) {
                ctxById.remove(id);
            }

            for (SpyContext ctx : ctxs) {
                ctxInstances.remove(ctx);
            }

            if (retransformer.isEnabled()) {
                retransformer.retransform(null, sdef.getMatcherSet(), true);
            }

        } else {
            log.info("Spy definition " + sdef.getName() + " has changed.");
        }
    }


    public SpyDefinition getSdef(String name) {
        return sdefs.get(name);
    }


    public synchronized Set<SpyDefinition> getSdefs() {
        Set<SpyDefinition> ret = new HashSet<SpyDefinition>();
        ret.addAll(sdefs.values());
        return ret;
    }

    @Override
    public byte[] transform(ClassLoader classLoader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] cbf) throws IllegalClassFormatException {
        if (log.isDebugEnabled()) {
            log.debug("Transform: " + className + " (" + classLoader + ")" + " cbr=" + classBeingRedefined);
        }
        if (className == null) {
            nullsEncountered.logCall(1);
            return null;
        }
        try {
            return doTransform(classLoader, className, cbf);
        } catch (Throwable e) {
            log.error("Error transforming class: " + className, e);
            return null;
        }
    }

    private byte[] doTransform(ClassLoader classLoader, String className, byte[] cbf) {
        if (Boolean.TRUE.equals(transformLock.get())) {
            return null;
        }

        if (cbf == null || cbf.length < 128 ||
            cbf[0] != (byte)0xca || cbf[1] != (byte)0xfe ||
            cbf[2] != (byte)0xba || cbf[3] != (byte)0xbe) {
            return null;
        }

        String clazzName = className.replace('/', '.');

        if (scriptsAuto) {
            bshAgent.probe(clazzName);
        }

        Set<String> currentTransforms = currentTransformsTL.get();
        if (currentTransforms.contains(clazzName)) {
            return null;
        } else {
            currentTransforms.add(clazzName);
        }

        long pt1 = System.nanoTime();

        List<SpyDefinition> found = new ArrayList<SpyDefinition>();

        if (log.isTraceEnabled()) {
            log.trace("Encountered class: %s", className);
        }

        long st1 = System.nanoTime();
        for (Map.Entry<String, SpyDefinition> e : sdefs.entrySet()) {
            SpyDefinition sdef = e.getValue();
            if (sdef.getMatcherSet().classMatch(clazzName)) {
                if (log.isDebugEnabled()) {
                    log.debug("MATCH: Class: " + clazzName + " matcher: " + sdef.getMatcherSet());
                }
                found.add(sdef);
            }
        }
        long st2 = System.nanoTime();

        spyLookups.logCall(st2 - st1);

        long lt1 = System.nanoTime();
        boolean tracerMatch = tracer.getMatcherSet().classMatch(clazzName);
        long lt2 = System.nanoTime();

        tracerLookups.logCall(lt2 - lt1);

        byte[] buf = cbf;

        if (found.size() > 0 || tracerMatch) {

            long tt1 = System.nanoTime();

            if (log.isDebugEnabled()) {
                log.debug("Transforming class: %s (sdefs found: %d; tracer match: %b)", className, found.size(), tracerMatch);
            }

            boolean doComputeFrames = computeFrames && (cbf[7] >= (byte) 0x32);

            ClassReader cr = new ClassReader(cbf);
            ClassLoader cl = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader().getParent();
            int flags = doComputeFrames ? SpyClassWriter.COMPUTE_FRAMES : 0;
            ClassWriter cw = useCustomResolver ? new SpyClassWriter(cr, flags, cl, resolver) : new ClassWriter(cr, flags);
            SpyClassVisitor scv = createVisitor(classLoader, clazzName, found, tracer, cw);
            cr.accept(scv, expandedFrames ? ClassReader.EXPAND_FRAMES : 0);

            if(scv.wasBytecodeModified()) {
                buf = cw.toByteArray();
            }

            long tt2 = System.nanoTime();
            classesTransformed.logCall(tt2 - tt1);
        }

        currentTransforms.remove(clazzName);

        long pt2 = System.nanoTime();
        classesProcessed.logCall(pt2 - pt1);

        if (dumpEnabled) {
            for (Pattern f : dumpFilters) {
                boolean matches = f.matcher(clazzName).matches();
                if (log.isTraceEnabled()) {
                    log.trace("Matching class " + clazzName + " against '" + f + "': " + matches);
                }
                if (matches) {
                    if (log.isDebugEnabled()) {
                        log.debug("Dumping class: " + clazzName);
                    }
                    dump(className, "in", cbf);
                    dump(className, "out", buf);
                    break;
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Finished transforming class: " + clazzName);
        }

        return buf == cbf ? null : buf;
    }

    private void dump(String className, String prefix, byte[] buf) {
        OutputStream os = null;
        try {
            File f = new File(new File(dumpDir, prefix), className + ".class");
            if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) {
                log.error("Cannot create directory: " + f.getParent());
            } else {
                os = new FileOutputStream(f);
                os.write(buf);
            }
        } catch (Exception e) {
            log.error("Error dumping class: " + className, e);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    log.error("Cannot close output stream (dumping " + className + ")", e);
                }
            }
        }
    }


    /**
     * Spawn class visitor for transformed class.
     *
     * @param className class name
     * @param found     spy definitions that match
     * @param cw        output (class writer)
     * @return class visitor for instrumenting this class
     */
    protected SpyClassVisitor createVisitor(ClassLoader classLoader, String className, List<SpyDefinition> found, Tracer tracer, ClassWriter cw) {
        return new SpyClassVisitor(this, classLoader, symbolRegistry, className, found, tracer, cw);
    }

}
