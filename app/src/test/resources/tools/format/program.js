#!/usr/bin/env node
// A program whose output must not change once minified. URL in a comment: http://example.com/a//b
'use strict';
/* A block comment
   over two lines */
const url = "http://example.com//path/*not a comment*/"; // trailing comment
const single = 'it\'s // not a comment';
const tpl = `a ${ 1 + 2 } b ${ `nested ${ "deep" } // still template` } c /* kept */ ${ { k: 1 }.k }`;
const re1 = /\/\/[a-z]+/g;
const re2 = /[/]+/;
const re3 = /a\/b/i;
function half(x) {
  return /* comment */ x / 2 / 1;
}
function early() {
  return
  42;
}
let a = 5, b = 3;
let c = a
++b
const d = a - -b;
const e = a + +b;
const h = a++ / 2;
const n = 1 .toString();
const t = typeof /re/;
const isIn = 'x' in { x: 1 };
const div = (a) / 2;
const obj = { get: 1, set: 2, 'key with spaces': "v  a  l" };
class K { #p = 7; static #s = 8; get p() { return this.#p; } static s() { return K.#s; } }
const list = [1, 2,
  3];
const ws = "tab	and  spaces";
const afterParen = (/x/).test("x");
const asi = [early(), half(10)];
const flags = /ab+c/gi.flags;
const spaced = /a  b|"x"/.test("a  b");
const slashStar = /=\/*x/.test("=//x");
const regexIn = /k/ instanceof RegExp;
const dividedTwice = 10 / 2 / 5;
const lineContinued = "one \
two";
const café = "naïve";
console.log(JSON.stringify({ url, single, tpl, r1: "a//bc".match(re1), r2: re2.source, r3: re3.test("A/B"), c, d, e, h, n, t, isIn, div, obj, p: new K().p, s: K.s(), list, ws, afterParen, asi, a, b, flags, spaced, slashStar, regexIn, dividedTwice, lineContinued, café }));
