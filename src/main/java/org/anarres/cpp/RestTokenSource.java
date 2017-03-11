package org.anarres.cpp;

import org.pcollections.Empty;
import org.pcollections.PStack;

import javax.annotation.Nonnull;
import java.io.IOException;

public class RestTokenSource extends Source {

    public FList<TokenS> rest;

    public RestTokenSource(FList<TokenS> rest) {
        this.rest = rest;
    }

    @Nonnull
    @Override
    public TokenS token() throws IOException, LexerException {
        if (rest.isEmpty()) {
            return new TokenS(new Token(Token.EOF, -1, -1,""), Empty.bag());
        }
        TokenS tokenS = rest.cur;
        rest = rest.next;
        return tokenS;
    }
}
