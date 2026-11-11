package com.shelfwise.core.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CSV cell rules for the order export (M22). */
class OrderExportTest {

    @Test
    void textThatExcelWouldRunAsAFormulaIsMadePlain() {
        assertThat(OrderExport.safeText("=HYPERLINK(\"http://x\")")).isEqualTo("'=HYPERLINK(\"http://x\")");
        assertThat(OrderExport.safeText("+91 bad")).isEqualTo("'+91 bad");
        assertThat(OrderExport.safeText("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(OrderExport.safeText("Zoya Joshi")).isEqualTo("Zoya Joshi");
    }

    @Test
    void commasQuotesAndLineBreaksAreQuoted() {
        assertThat(OrderExport.cell("Rao, Arjun")).isEqualTo("\"Rao, Arjun\"");
        assertThat(OrderExport.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(OrderExport.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(OrderExport.cell(null)).isEmpty();
    }
}
