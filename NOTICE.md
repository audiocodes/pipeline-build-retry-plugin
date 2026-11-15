# Third-party notices

This plugin follows the behaviour of the Naginator plugin (https://github.com/jenkinsci/naginator-plugin), which is distributed under the MIT License. The following parts are derived from or based on Naginator:

- `LogScanner`: the line-by-line log matching with a timeout follows Naginator's `parseLog`, and the wrapper that makes regex matching interruptible closely follows its `InterruptibleCharSequence`;
- `DelaySpec`: the progressive delay formula and its defaults follow Naginator's `ProgressiveDelay`;
- `FixedDelay` and `ProgressiveDelay`: the class names and the "Fixed" and "Progressive" descriptor labels mirror those of Naginator's classes of the same names.

A comparison with the full Naginator history found no other substantive shared code.

The Naginator plugin's notice, which applies to those parts:

```
The MIT License

Copyright (c) 2015-2016 IKEDA Yasuyuki, and the Naginator plugin contributors (Andrew Bayer, Nicolas De Loof, Tomer Galun and others).

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

`LogScanner`, `DelaySpec`, `FixedDelay` and `ProgressiveDelay` also carry this copyright line in their file headers.
