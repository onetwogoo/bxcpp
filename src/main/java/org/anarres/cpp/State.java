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

/* pp */ class State {

    public final boolean parent;
    public final boolean active;
    public final boolean sawElse;

    /* pp */ State() {
        this.parent = true;
        this.active = true;
        this.sawElse = false;
    }

    private State(boolean parent, boolean active, boolean sawElse) {
        this.parent = parent;
        this.active = active;
        this.sawElse = sawElse;
    }

    /* pp */ State(State parent) {
        this.parent = parent.isParentActive() && parent.isActive();
        this.active = true;
        this.sawElse = false;
    }

    /* Required for #elif */

    /* pp */ boolean isParentActive() {
        return parent;
    }

    State withParentActive(boolean parentActive) {
        return new State(parentActive, this.active, this.sawElse);
    }

    /* pp */ boolean isActive() {
        return active;
    }

    State withActive(boolean active) {
        return new State(this.parent, active, this.sawElse);
    }

    /* pp */ boolean sawElse() {
        return sawElse;
    }

    State withSawElse() {
        return new State(this.parent, this.active, true);
    }

    @Override
    public String toString() {
        return "parent=" + parent
                + ", active=" + active
                + ", sawelse=" + sawElse;
    }

    @Override
    public boolean equals(Object obj) {
        State o = (State)obj;
        return o.parent == this.parent && o.active == this.active && o.sawElse == this.sawElse;
    }

    @Override
    public int hashCode() {
        int parentValue = parent ? 1 : 0;
        int activeValue = active ? 1 : 0;
        int sawElseValue = sawElse ? 1 : 0;
        return (parentValue << 2) | (activeValue << 1) | sawElseValue;
    }
}
