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

package com.jitlogic.zorka.core.spy;

import com.jitlogic.zorka.common.util.ZorkaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.jitlogic.zorka.core.spy.SpyMatcher.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Groups all matchers and provides methods for matching that query all
 * matchers it contains.
 */
public class PatternMatcherSet implements SpyMatcherSet {

    private static final Logger log = LoggerFactory.getLogger(PatternMatcherSet.class);

    /**
     * List of (included) matchers.
     */
    private List<SpyMatcher> matchers = new ArrayList<SpyMatcher>();


    /**
     * Creates new matcher set (with empty list of matchers).
     */
    public PatternMatcherSet() {
    }

    public PatternMatcherSet(PatternMatcherSet orig) {
        matchers.addAll(orig.getMatchers());
    }

    /**
     * Creates new matcher set and populates it with supplied matchers.
     *
     * @param matchers initial matchers
     */
    public PatternMatcherSet(SpyMatcher... matchers) {
        includeInternal(matchers);
    }


    /**
     * Creates new matcher set and populates it with matchers from original matcher set and additional supplied matchers.
     *
     * @param orig     original matcher set
     * @param matchers supplied matchers
     */
    public PatternMatcherSet(PatternMatcherSet orig, SpyMatcher... matchers) {
        this.matchers.addAll(orig.matchers);
        includeInternal(matchers);
    }


    @Override
    public boolean classMatch(String className) {
        for (SpyMatcher matcher : matchers) {
            int flags = matcher.getFlags();

            if ((0 != (flags & BY_CLASS_NAME)) && match(matcher.getClassPattern(), className)) {
                // Return true or false depending on whether this is normal or inverted match
                return finalClassMatch(matcher);
            }

            if (0 != (flags & (BY_CLASS_ANNOTATION | BY_INTERFACE | BY_METHOD_ANNOTATION | BY_SUPERCLASS))) {
                return finalClassMatch(matcher);
            }
        }

        return false;
    }



    private boolean finalClassMatch(SpyMatcher matcher) {
        if (matcher.hasFlags(EXCLUDE_MATCH)) {
            return !"[a-zA-Z0-9_]+".equals(matcher.getMethodPattern().toString());
        } else {
            return true;
        }
    }


    private boolean matchesAnnotation(Annotation[] annotations, Pattern pattern) {
        for (Annotation a : annotations) {
            String name = "L" + a.annotationType().getName() + ";";
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }


    @Override
    public boolean classMatch(Class<?> clazz, boolean matchMethods) {
        try {
            String className = clazz.getName();
            for (SpyMatcher matcher : matchers) {

                if (matcher.hasFlags(BY_CLASS_NAME) && !match(matcher.getClassPattern(), className)) {
                    continue;
                }

                if (matcher.hasFlags(BY_INTERFACE) && !ZorkaUtil.instanceOf(clazz, matcher.getClassPattern())) {
                    continue;
                }

                if (matcher.hasFlags(BY_CLASS_ANNOTATION) && !matchesAnnotation(clazz.getAnnotations(), matcher.getClassPattern())) {
                    continue;
                }

                if ((matchMethods && !methodMatch(clazz, matcher)) || (!matchMethods && matcher.hasFlags(BY_METHOD_ANNOTATION))) {
                    continue;
                }

                return finalClassMatch(matcher);
            }
        } catch (NoClassDefFoundError e) {
            log.warn("NoClassDefFoundError occured when inspecting " + clazz
                    + " for retransform. Consider disabling method filtering in retransformation.");
        }

        return false;
    }


    private static String createDescriptor(Method method) {
        StringBuilder sb = new StringBuilder();

        sb.append("(");

        for (Class<?> arg : method.getParameterTypes()) {
            String type = arg.getName();
            sb.append(SpyMatcher.toTypeCode(type));
        }

        sb.append(")");

        String type = method.getReturnType().getName();
        sb.append(SpyMatcher.toTypeCode(type));

        return sb.toString();
    }


    private boolean methodMatch(Class<?> clazz, SpyMatcher matcher) {

        for (Method method : clazz.getDeclaredMethods()) {
            if (matcher.hasFlags(BY_METHOD_NAME) && !match(matcher.getMethodPattern(), method.getName())) {
                continue;
            }

            if (matcher.hasFlags(BY_METHOD_ANNOTATION) && !matchesAnnotation(method.getAnnotations(), matcher.getMethodPattern())) {
                continue;
            }

            if (matcher.getAccess() != 0 && 0 == (matcher.getAccess() & method.getModifiers())) {
                continue;
            }

            if (matcher.hasFlags(BY_METHOD_SIGNATURE) && !match(matcher.getSignaturePattern(), createDescriptor(method))) {
                continue;
            }

            return true;
        }

        return false;
    }


    @Override
    public boolean methodMatch(String className, List<String> superclasses,
                               List<String> classAnnotations, List<String> classInterfaces,
                               int access, String methodName,
                               String methodSignature, List<String> methodAnnotations) {

        for (SpyMatcher matcher : matchers) {
            int flags = matcher.getFlags();

            if ((0 != (flags & BY_CLASS_NAME) && match(matcher.getClassPattern(), className))
                    || (0 != (flags & BY_SUPERCLASS) && match(matcher.getClassPattern(), superclasses))
                    || (0 != (flags & BY_CLASS_ANNOTATION) && match(matcher.getClassPattern(), classAnnotations))
                    || (0 != (flags & BY_INTERFACE) && match(matcher.getClassPattern(), classInterfaces))) {

                if ((0 != (flags & NO_CONSTRUCTORS) && ("<init>".equals(methodName) || "<clinit>".equals(methodName)))
                        || (0 != (flags & NO_ACCESSORS) && isAccessor(methodName, methodSignature))
                        || (0 != (flags & NO_COMMONS) && COMMON_METHODS.contains(methodName))) {
                    return false;
                }

                // Method access-name-signature check
                if ((0 == matcher.getAccess() || 0 != (access & matcher.getAccess()))
                        && (0 == (flags & BY_METHOD_NAME) || match(matcher.getMethodPattern(), methodName))
                        && (0 == (flags & BY_METHOD_SIGNATURE) || match(matcher.getSignaturePattern(), methodSignature))
                        && (0 == (flags & BY_METHOD_ANNOTATION) || methodAnnotations == null
                        || match(matcher.getMethodPattern(), methodAnnotations))) {
                    // Return true or false depending on whether this is normal or inverted match
                    return !matcher.hasFlags(SpyMatcher.EXCLUDE_MATCH);
                }

                // Method annotation is undecidable at this point, always return true
                if (0 != (flags & BY_METHOD_ANNOTATION) && methodAnnotations == null) {
                    return true;
                }

            }
        }

        return false;
    }


    private boolean isAccessor(String methodName, String methodSignature) {
        return (methodName.startsWith("set") && methodSignature.endsWith(")V"))
                || (methodName.startsWith("get") && methodSignature.startsWith("()"))
                || (methodName.startsWith("is") && methodSignature.startsWith("()"))
                || (methodName.startsWith("has") && methodSignature.startsWith("()"));
    }


    public boolean hasMethodAnnotations() {
        for (SpyMatcher matcher : matchers) {
            if (0 != (matcher.getFlags() & SpyMatcher.BY_METHOD_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns true supplied string matches given regular expression pattern.
     *
     * @param pattern   regular expression pattern
     * @param candidate candidate
     * @return true if candidate matches
     */
    private boolean match(Pattern pattern, String candidate) {
        return pattern.matcher(candidate).matches();
    }


    /**
     * Returns true if any of supplied strings matches given pattern.
     *
     * @param pattern    regular expression pattern
     * @param candidates candidates
     * @return true if any candidate matches
     */
    private boolean match(Pattern pattern, List<String> candidates) {
        for (String candidate : candidates) {
            if (pattern.matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }


    /**
     * Adds new matchers to matcher set. New matchers are appended at the end of list (and thus will be checked last).
     *
     * @param includes appended matchers
     */
    private void includeInternal(SpyMatcher... includes) {

        for (SpyMatcher m : includes) {
            if (m != null) {
                if (matchers.size() == 0 || m.getPriority() >= matchers.get(matchers.size() - 1).getPriority()) {
                    matchers.add(m);
                } else if (m.getPriority() < matchers.get(0).getPriority()) {
                    matchers.add(0, m);
                } else {
                    for (int i = 0; i < matchers.size(); i++) {
                        if (matchers.get(i).getPriority() > m.getPriority()) {
                            matchers.add(i, m);
                            break;
                        }
                    }
                }
            }
        }
    }

    public PatternMatcherSet include(SpyMatcher... includes) {
        PatternMatcherSet ret = new PatternMatcherSet(this);
        ret.includeInternal(includes);
        return ret;
    }

    public List<SpyMatcher> getMatchers() {
        return matchers;
    }

    @Override
    public void clear() {
        matchers.clear();
    }

    @Override
    public String toString() {
        return matchers.toString();
    }
}
