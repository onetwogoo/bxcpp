package org.anarres.cpp;

import java.util.*;

public class ActionCollector {
    /* Recording actions */
    public ActionSequence actions = new ActionSequence();
    private List<TokenS> currentTokens = new ArrayList<TokenS>();

    public void getToken(Token token, Source source) {
        if (token.getType() == Token.P_LINE || token.getType() == Token.EOF)
            return;
        TokenS tokenS = new TokenS(token, source.disabledMacros());
        currentTokens.add(tokenS);
    }

    public void ungetToken(Token token) {
        if (token != currentTokens.get(currentTokens.size() - 1).token)
            throw new Error("Unget expected " + token + " got " + currentTokens.get(currentTokens.size() - 1).token);
        currentTokens.remove(currentTokens.size() - 1);
    }

    public int numToken() {
        return currentTokens.size();
    }

    /**
     * Delete all tokens in currentTokens except the last one
     * Then skip the last token
     */
    public void skipLast() {
        if (currentTokens.isEmpty()) {
            throw new Error("skipLast when empty");
        }
        TokenS last = currentTokens.get(currentTokens.size() - 1);
        if (currentTokens.size() > 1) {
            currentTokens.remove(currentTokens.size() - 1);
            actions.actions.add(new Replace(currentTokens, Collections.<MapSeg>emptyList(), Collections.<String>emptySet()));
        }
        actions.actions.add(new Skip(last));
        currentTokens = new ArrayList<TokenS>();
    }

    /**
     * Delete all tokens in currentTokens
     */
    public void delete() {
        if (!currentTokens.isEmpty()) {
            actions.actions.add(new Replace(currentTokens, Collections.<MapSeg>emptyList(), Collections.<String>emptySet()));
            currentTokens = new ArrayList<TokenS>();
        }
    }

    /**
     * Replace all tokens with producedTokens, which may be filled later
     */
    public void replaceWith(List<Token> newTokens) {
        actions.actions.add(new Replace(currentTokens, Collections.<MapSeg>singletonList(
                new New(newTokens)
        ), Collections.<String>emptySet()));
        currentTokens = new ArrayList<TokenS>();
    }

    /**
     * Replace all tokens with a mapping, which will be filled later
     */
    public void replaceWith(List<MapSeg> mapping, Set<String> disables) {
        actions.actions.add(new Replace(currentTokens, mapping, disables));
        currentTokens = new ArrayList<TokenS>();
    }
}
