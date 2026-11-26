package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.text.Arg;
import java.util.Arrays;
import java.util.List;

final class UpmReportAssertions {
    private UpmReportAssertions() { /* Test helper only. */ }

    static List<String> unparsedValues(ModuleFileTransactions.Report report) {
        return Arrays.stream(report.getArgs()).map(Arg.Unparsed.class::cast).map(Arg.Unparsed::value).toList();
    }
}
