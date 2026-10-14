"""Convert an HTML file to plain text (data only). Usage: python -I html2txt.py IN.html OUT.txt"""
import sys
import html
import re
from html.parser import HTMLParser


class P(HTMLParser):
    BLOCK = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "dt", "dd", "pre", "section", "article", "blockquote"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "noscript"):
            self.skip += 1
        if tag in self.BLOCK:
            self.out.append("\n")
        if tag == "a":
            for k, v in attrs:
                if k == "id" or k == "name":
                    self.out.append("[#%s]" % v)
        if tag in ("h1", "h2", "h3", "h4", "dt"):
            for k, v in attrs:
                if k == "id":
                    self.out.append("[#%s] " % v)

    def handle_endtag(self, tag):
        if tag in ("script", "style", "noscript"):
            self.skip = max(0, self.skip - 1)
        if tag in self.BLOCK:
            self.out.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.out.append(data)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "r", encoding="utf-8", errors="replace") as f:
        t = f.read()
    p = P()
    p.feed(t)
    s = "".join(p.out)
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    s = re.sub(r"\n\s*\n+", "\n\n", s)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(s)
    print(dst, len(s))


if __name__ == "__main__":
    main()
