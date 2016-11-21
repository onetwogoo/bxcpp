package org.anarres.cpp;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Set;

import static org.anarres.cpp.Token.EOF;

/**
 * Created by jiayu on 11/11/2016.
 */
public class DisableListSource extends Source {

    Token token;
    Set<String> disables;

    public DisableListSource(TokenS tokenS) {
        this.token = tokenS.token;
        this.disables = tokenS.disables;
    }

    @Nonnull
    public Token token() throws IOException, LexerException {
        if (token == null) {
            return new Token(EOF, -1, -1, "");
        }
        Token _token = token;
        token = null;
        return _token;
    }

    @Override
    boolean isExpanding(@Nonnull Macro m) {
        return disables.contains(m.getName());
    }
}
