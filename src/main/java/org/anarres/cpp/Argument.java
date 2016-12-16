/*
 * Anarres C Preprocessor
 * Copyright (c) 2007-2015, Shevek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.anarres.cpp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * A macro argument.
 *
 * This encapsulates a raw and preprocessed token stream.
 */
/* pp */ class Argument extends ArrayList<TokenS> {

    public List<Integer> indicies = new ArrayList<Integer>();
    public ActionSequence actions;
    private List<TokenS> expansion;

    public Argument() {
        this.expansion = null;
    }

    public void addToken(@Nonnull TokenS tok, int index) {
        add(tok);
        indicies.add(index);
    }

    /* pp */ void expand(@Nonnull Preprocessor p)
            throws IOException,
            LexerException {
        /* Cache expansion. */
        if (expansion == null) {
            this.expansion = p.expand(this);
            // System.out.println("Expanded arg " + this);
        }
    }

    @Nonnull
    public Iterator<TokenS> expansion() {
        return expansion.iterator();
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Argument(");
        // buf.append(super.toString());
        buf.append("raw=[ ");
        for (int i = 0; i < size(); i++)
            buf.append(get(i).token.getText());
        buf.append(" ];expansion=[ ");
        if (expansion == null)
            buf.append("null");
        else
            for (TokenS token : expansion)
                buf.append(token.token.getText());
        buf.append(" ])");
        return buf.toString();
    }

}
