package com.stoicera.einvoice.validation.repair;

import com.stoicera.einvoice.validation.internal.SecureXml;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * An uploaded document as text, with the exact character span of every element.
 *
 * <p>Two views of the same bytes. The DOM comes from {@link SecureXml} — the module's one hardened
 * parser — and answers the semantic questions (which element, which namespace, which value). The
 * spans come from a small scanner over the decoded text and answer "where exactly": the scanner
 * only has to recognise markup boundaries, because the DOM parse has already proven the text
 * well-formed. Both list elements in document order, which is how an element is matched to its
 * span; the local names are compared on the way, and any disagreement means "no proposal" rather
 * than an edit in the wrong place.
 *
 * <p>Why not StAX locations: the JDK's character offsets drift by one around CR LF line ends
 * (measured while building this), and a proposal that is off by one character is a broken document.
 */
final class XmlText {

  /** Character span of one element: its start tag and its end tag (equal for {@code <a/>}). */
  record Span(String qname, int startTagStart, int startTagEnd, int endTagStart, int endTagEnd) {

    boolean isEmptyTag() {
      return endTagStart == startTagEnd && endTagEnd == startTagEnd;
    }
  }

  private final String text;
  private final Charset charset;
  private final Document dom;
  private final Map<Element, Span> spans;

  private XmlText(String text, Charset charset, Document dom, Map<Element, Span> spans) {
    this.text = text;
    this.charset = charset;
    this.dom = dom;
    this.spans = spans;
  }

  /**
   * Indexes {@code xml}, or answers empty when the bytes are not well-formed, use a charset this
   * JVM cannot round-trip exactly, or the scanner and the parser disagree.
   */
  static Optional<XmlText> of(byte[] xml) {
    Optional<Document> parsed = SecureXml.parse(xml);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    Document dom = parsed.get();
    Optional<Charset> charset = charsetOf(dom);
    if (charset.isEmpty()) {
      return Optional.empty();
    }
    String text = new String(xml, charset.get());
    // Edits must not change a single byte they do not mean to: if decoding and re-encoding the
    // untouched text does not reproduce the upload exactly, no edit on it can be trusted either.
    if (!Arrays.equals(text.getBytes(charset.get()), xml)) {
      return Optional.empty();
    }
    try {
      List<Span> scanned = scan(text);
      NodeList elements = dom.getElementsByTagName("*");
      if (elements.getLength() != scanned.size()) {
        return Optional.empty();
      }
      Map<Element, Span> spans = new IdentityHashMap<>();
      for (int i = 0; i < scanned.size(); i++) {
        Element element = (Element) elements.item(i);
        Span span = scanned.get(i);
        if (!localName(span.qname()).equals(element.getLocalName())) {
          return Optional.empty();
        }
        spans.put(element, span);
      }
      return Optional.of(new XmlText(text, charset.get(), dom, spans));
    } catch (RuntimeException e) {
      // The scanner ran off the end of text the parser accepted — a disagreement, not a crash.
      return Optional.empty();
    }
  }

  String text() {
    return text;
  }

  Document dom() {
    return dom;
  }

  Span span(Element element) {
    return spans.get(element);
  }

  /** The text with {@code edits} applied; edits must not overlap. */
  String apply(List<TextEdit> edits) {
    List<TextEdit> sorted =
        edits.stream().sorted(Comparator.comparingInt(TextEdit::offset)).toList();
    StringBuilder out = new StringBuilder(text.length() + 256);
    int cursor = 0;
    for (TextEdit edit : sorted) {
      if (edit.offset() < cursor) {
        throw new IllegalArgumentException("overlapping edits");
      }
      out.append(text, cursor, edit.offset()).append(edit.replacement());
      cursor = edit.end();
    }
    return out.append(text, cursor, text.length()).toString();
  }

  /** Encodes {@code corrected} in the upload's own charset. */
  byte[] encode(String corrected) {
    return corrected.getBytes(charset);
  }

  /**
   * The whitespace that indents the line {@code tagStart} sits on, or empty when the tag does not
   * start its own line (a document written on one line gets its insertions inline, too).
   */
  Optional<String> indentBefore(int tagStart) {
    int i = tagStart;
    while (i > 0 && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) {
      i--;
    }
    if (i > 0 && text.charAt(i - 1) == '\n') {
      return Optional.of(text.substring(i, tagStart));
    }
    return Optional.empty();
  }

  /** The line break the document uses — CR LF if its first line ends that way, else LF. */
  String lineBreak() {
    int lf = text.indexOf('\n');
    return lf > 0 && text.charAt(lf - 1) == '\r' ? "\r\n" : "\n";
  }

  /**
   * The declared encoding first: the JDK's DOM reports {@code getInputEncoding()} as UTF-8 even for
   * a document declared and parsed as ISO-8859-1 (observed with the JDK's built-in parser). A wrong
   * guess cannot slip through either way — {@link #of} rejects any charset that does not round-trip
   * the upload.
   */
  private static Optional<Charset> charsetOf(Document dom) {
    String name = dom.getXmlEncoding() != null ? dom.getXmlEncoding() : dom.getInputEncoding();
    if (name == null) {
      return Optional.of(StandardCharsets.UTF_8);
    }
    try {
      return Optional.of(Charset.forName(name));
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      return Optional.empty();
    }
  }

  private static String localName(String qname) {
    int colon = qname.indexOf(':');
    return colon < 0 ? qname : qname.substring(colon + 1);
  }

  /**
   * Lists every element's span in document order. Assumes well-formed input (the DOM parse ran
   * first) and no DOCTYPE ({@link SecureXml} rejects one), so it only needs to step over comments,
   * CDATA sections, processing instructions and quoted attribute values.
   */
  private static List<Span> scan(String s) {
    List<int[]> bounds = new ArrayList<>(); // per span: [startTagStart, startTagEnd, endStart, end]
    Deque<Integer> open = new ArrayDeque<>();
    int i = 0;
    while (true) {
      int lt = s.indexOf('<', i);
      if (lt < 0) {
        break;
      }
      if (s.startsWith("<!--", lt)) {
        i = after(s, "-->", lt + 4);
      } else if (s.startsWith("<![CDATA[", lt)) {
        i = after(s, "]]>", lt + 9);
      } else if (s.startsWith("<?", lt)) {
        i = after(s, "?>", lt + 2);
      } else if (s.startsWith("</", lt)) {
        int end = after(s, ">", lt + 2);
        int[] b = bounds.get(open.pop());
        b[2] = lt;
        b[3] = end;
        i = end;
      } else {
        int gt = startTagEnd(s, lt + 1);
        boolean empty = s.charAt(gt - 1) == '/';
        int index = bounds.size();
        bounds.add(new int[] {lt, gt + 1, gt + 1, gt + 1});
        if (!empty) {
          open.push(index);
        }
        i = gt + 1;
      }
    }
    if (!open.isEmpty()) {
      throw new IllegalStateException("unclosed element");
    }
    return bounds.stream()
        .map(b -> new Span(qnameAt(s, b[0] + 1), b[0], b[1], b[2], b[3]))
        .toList();
  }

  private static int after(String s, String token, int from) {
    int at = s.indexOf(token, from);
    if (at < 0) {
      throw new IllegalStateException("unterminated markup");
    }
    return at + token.length();
  }

  /** Index of the {@code >} that closes the start tag beginning before {@code from}. */
  private static int startTagEnd(String s, int from) {
    char quote = 0;
    for (int j = from; j < s.length(); j++) {
      char c = s.charAt(j);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return j;
      }
    }
    throw new IllegalStateException("unterminated start tag");
  }

  private static String qnameAt(String s, int from) {
    int j = from;
    while (j < s.length()) {
      char c = s.charAt(j);
      if (c == '>' || c == '/' || Character.isWhitespace(c)) {
        break;
      }
      j++;
    }
    return s.substring(from, j);
  }
}
