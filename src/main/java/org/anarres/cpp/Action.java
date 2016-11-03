package org.anarres.cpp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class ActionSequence {
    public List<Action> actions = new ArrayList<Action>();
    public List<byte[]> environments = new ArrayList<byte[]>();
}

abstract class Action {
}

class Skip extends Action {
    public TokenS token;

    public Skip(TokenS token) {
        this.token = token;
    }

    @Override
    public String toString() {
        return "Skip " + token;
    }
}

class Replace extends Action {
    public List<TokenS> old;
    public List<MapSeg> mapping;
    public Set<String> disables;

    public Replace(List<TokenS> old, List<MapSeg> mapping, Set<String> disables) {
        this.old = old;
        this.mapping = mapping;
        this.disables = disables;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Replace o=");
        for (TokenS token: old) {
            sb.append(token).append(" ");
        }
        sb.append(" mpn=");
        for (MapSeg seg:mapping) {
            sb.append(seg).append(" ");
        }
        sb.append(" dsbl=");
        for (String macro:disables) {
            sb.append(macro).append(" ");
        }
        return sb.toString();
    }
}

class TokenS {
    public Token token;
    public Set<String> disables;

    public TokenS(Token token, Set<String> disables) {
        this.token = token;
        this.disables = disables;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (token.getType() == Token.NL) sb.append("\\n");
        else sb.append(token.getText());
//        sb.append("{");
//        for (String macro:disables) {
//            sb.append(macro).append(" ");
//        }
//        sb.append("}");
        return sb.toString();
    }
}

abstract class MapSeg {
}

class Sub extends MapSeg {
    public List<Integer> indicies;
    public ActionSequence actions;

    public Sub(List<Integer> indicies, ActionSequence actions) {
        this.indicies = indicies;
        this.actions = actions;
    }
}

class New extends MapSeg {
    public List<Token> tokens;

    public New(List<Token> tokens) {
        this.tokens = tokens;
    }
}