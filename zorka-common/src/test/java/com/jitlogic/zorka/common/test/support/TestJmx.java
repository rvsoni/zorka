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

package com.jitlogic.zorka.common.test.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author RLE <rafal.lewczuk@gmail.com>
 */
public class TestJmx implements TestJmxMBean {

    public long nom, div;
    private Map<String, String> strMap = new LinkedHashMap<String, String>();

    public long getNom() {
        return nom;
    }

    public long getDiv() {
        return div;
    }

    public Map<String, String> getStrMap() {
        return strMap;
    }

    @Override
    public String someOp() {
        return "Hello world";
    }

    @Override
    public String someOp(String s) {
        return "Hello " + s;
    }

    @Override
    public String someOp(String s, int x) {
        return "Hello " + s + x;
    }

    public void put(String key, String val) {
        strMap.put(key, val);
    }

    public void setNom(long nom) {
        this.nom = nom;
    }

    public void setDiv(long div) {
        this.div = div;
    }
}
