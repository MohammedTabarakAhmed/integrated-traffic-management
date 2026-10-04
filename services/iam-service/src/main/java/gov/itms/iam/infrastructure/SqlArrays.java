package gov.itms.iam.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Text-array helpers. Values are validated codes that never contain commas, so arrays are passed as a comma-joined
 * parameter and converted in SQL with {@code string_to_array(:p, ',')}, keeping every statement parameterised.
 */
final class SqlArrays {

    private SqlArrays() {
    }

    static String join(Collection<String> values) {
        values.forEach(v -> {
            if (v.indexOf(',') >= 0) {
                throw new IllegalArgumentException("array values must not contain commas");
            }
        });
        return String.join(",", values);
    }

    static List<String> read(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
