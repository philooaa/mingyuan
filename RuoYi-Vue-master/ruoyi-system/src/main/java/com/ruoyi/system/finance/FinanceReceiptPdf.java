package com.ruoyi.system.finance;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** PDF uses only the immutable receipt snapshot and a bundled OFL Chinese font. */
public final class FinanceReceiptPdf {
  private FinanceReceiptPdf() {}

  public static byte[] render(Map<String, Object> snapshot) throws IOException {
    try (var doc = new PDDocument();
        var fontStream =
            FinanceReceiptPdf.class.getResourceAsStream("/finance/fonts/NotoSansSC-Regular.ttf");
        var out = new ByteArrayOutputStream()) {
      if (fontStream == null) throw new IOException("missing bundled Chinese font");
      var font = PDType0Font.load(doc, fontStream, true);
      try (var layout = new Layout(doc, font)) {
        layout.line(value(snapshot, "institutionTitle", "名远教育"), 23);
        layout.line("FREE".equals(value(snapshot, "channel", "")) ? "课程学费减免凭证" : "课程学费收据", 18);
        if ("MOCK".equals(value(snapshot, "financeMode", "REAL")))
          layout.line("模拟演示 - 非真实收款凭证", 12);
        layout.line("收据编号：" + value(snapshot, "receiptNo", ""), 11);
        layout.line("付款编号：" + value(snapshot, "paymentNo", ""), 11);
        layout.line("报名编号：" + value(snapshot, "enrollmentCode", ""), 11);
        layout.line("学生姓名：" + value(snapshot, "studentName", ""), 12);
        layout.line("课程名称：" + value(snapshot, "courseClassName", ""), 12);
        layout.items(snapshot.get("orderItems"));
        layout.line("校区：" + value(snapshot, "campusName", ""), 12);
        layout.line("课程原价：" + value(snapshot, "originalAmount", "0.00") + " 元", 12);
        layout.line("优惠减免：" + value(snapshot, "discountAmount", "0.00") + " 元", 12);
        layout.line("本次实收：" + value(snapshot, "amount", "0.00") + " 元", 17);
        layout.line("付款人：" + value(snapshot, "payerName", ""), 12);
        layout.line("收款方式：" + channel(value(snapshot, "channel", "")), 12);
        layout.line("收款时间：" + value(snapshot, "paidTime", ""), 12);
        layout.line("经办人：" + value(snapshot, "operatorName", ""), 12);
        layout.line("本凭证记录当时收款事实，不等同于税务发票。", 10);
        layout.line("如有后续退费，请以关联退费记录及到账凭证为准。", 10);
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static String value(Map<String, Object> s, String key, String fallback) {
    return Objects.toString(s.get(key), fallback);
  }

  private static String channel(String s) {
    return switch (s) {
      case "CASH" -> "现金";
      case "BANK_TRANSFER" -> "银行转账";
      case "WECHAT_OFFLINE" -> "线下微信收款";
      case "MOCK" -> "本地模拟";
      case "FREE" -> "零元减免";
      default -> s;
    };
  }

  private static final class Layout implements AutoCloseable {
    final PDDocument doc;
    final PDType0Font font;
    PDPageContentStream stream;
    float y;
    int page;

    Layout(PDDocument doc, PDType0Font font) throws IOException {
      this.doc = doc;
      this.font = font;
      next();
    }

    void next() throws IOException {
      if (stream != null) stream.close();
      var p = new PDPage(PDRectangle.A4);
      doc.addPage(p);
      stream = new PDPageContentStream(doc, p);
      y = 780;
      page++;
      stream.setNonStrokingColor(0.08f, 0.2f, 0.36f);
      text("名远教育 | 课程财务凭证", 9, 44, 816);
      text("第 " + page + " 页", 9, 510, 30);
    }

    void text(String s, float size, float x, float at) throws IOException {
      stream.beginText();
      stream.setFont(font, size);
      stream.newLineAtOffset(x, at);
      stream.showText(s);
      stream.endText();
    }

    /** 购物车订单逐门列出课程，一张收据也能看清这一单收了哪几门课。 */
    void items(Object raw) throws IOException {
      if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) return;
      line("课程明细：", 12);
      int index = 1;
      for (Object entry : list) {
        if (!(entry instanceof java.util.Map<?, ?> m)) continue;
        StringBuilder text = new StringBuilder();
        text.append("  ").append(index++).append(". ").append(field(m, "courseName"));
        String meta = join(field(m, "gradeName"), field(m, "subjectName"));
        if (!meta.isEmpty()) text.append("｜").append(meta);
        String lessons = field(m, "lessonCount"), unit = field(m, "unitPrice");
        if (!lessons.isEmpty()) {
          text.append("（").append(lessons).append(" 课次");
          if (!unit.isEmpty()) text.append(" × ").append(unit).append(" 元");
          text.append("）");
        }
        String amount = field(m, "amount");
        if (!amount.isEmpty()) text.append("   ").append(amount).append(" 元");
        line(text.toString(), 11);
      }
    }

    private static String field(java.util.Map<?, ?> m, String key) {
      Object v = m.get(key);
      return v == null ? "" : v.toString().trim();
    }

    private static String join(String a, String b) {
      if (a.isEmpty()) return b;
      if (b.isEmpty()) return a;
      return a + " · " + b;
    }

    void line(String input, float size) throws IOException {
      // Wrap by measured glyph widths, not character count; skip unsupported control/glyphs safely.
      StringBuilder buffer = new StringBuilder();
      float width = 0;
      for (int cp : input.codePoints().toArray()) {
        if (Character.isISOControl(cp)) {
          if (cp == '\n') {
            flush(buffer, size);
            width = 0;
          }
          continue;
        }
        String glyph = new String(Character.toChars(cp));
        float w;
        try {
          w = font.getStringWidth(glyph) / 1000 * size;
        } catch (IllegalArgumentException missing) {
          glyph = "?";
          w = font.getStringWidth(glyph) / 1000 * size;
        }
        if (width + w > 505) {
          flush(buffer, size);
          width = 0;
        }
        buffer.append(glyph);
        width += w;
      }
      flush(buffer, size);
      y -= 7;
    }

    void flush(StringBuilder s, float size) throws IOException {
      if (y < 65) next();
      text(s.toString(), size, 44, y);
      s.setLength(0);
      y -= size + 8;
    }

    public void close() throws IOException {
      if (stream != null) stream.close();
    }
  }
}
