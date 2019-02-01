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

package com.jitlogic.zorka.core.spy.tuner;

import com.jitlogic.zorka.common.tracedata.SymbolRegistry;
import com.jitlogic.zorka.common.util.BitVector;

public class CidsMidsZtxReader extends AbstractZtxReader {

    private BitVector cids;
    private BitVector mids;
    private SymbolRegistry registry;

    public CidsMidsZtxReader(SymbolRegistry registry, BitVector cids, BitVector mids) {
        this.cids = cids;
        this.mids = mids;
        this.registry = registry;
    }

    @Override
    public void add(String p, String c, String m, String s) {
        String cl = p.length() > 0 ? p+"."+c : c;
        cids.set(registry.symbolId(cl));
        mids.set(registry.methodId(cl, m, s));
    }
}
