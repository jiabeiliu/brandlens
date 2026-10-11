package com.jiabeiliu.brandlens.config;

import com.pgvector.PGvector;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Maps {@link PGvector} to PostgreSQL's {@code vector} column type.
 *
 * <p>pgvector-java (0.1.1) ships no Hibernate type, so this minimal
 * {@link UserType} bridges the gap: on write the vector is sent as its
 * text literal (e.g. {@code "[0.1, 0.2, ...]"}) with {@link Types#OTHER},
 * which PostgreSQL casts to {@code vector}; on read the literal is parsed
 * back via {@code new PGvector(String)}.
 */
public class PGvectorType implements UserType<PGvector> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<PGvector> returnedClass() {
        return PGvector.class;
    }

    @Override
    public boolean equals(PGvector x, PGvector y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        return Arrays.equals(x.toArray(), y.toArray());
    }

    @Override
    public int hashCode(PGvector x) {
        return Arrays.hashCode(x.toArray());
    }

    @Override
    public PGvector nullSafeGet(ResultSet rs, int position,
                               SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        String literal = rs.getString(position);
        if (rs.wasNull()) {
            return null;
        }
        return new PGvector(literal);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, PGvector value, int index,
                           SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value.getValue(), Types.OTHER);
        }
    }

    @Override
    public PGvector deepCopy(PGvector value) {
        return value == null ? null : new PGvector(value.toArray());
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(PGvector value) {
        return value == null ? null : value.toArray();
    }

    @Override
    public PGvector assemble(Serializable cached, Object owner) {
        return cached == null ? null : new PGvector((float[]) cached);
    }
}
