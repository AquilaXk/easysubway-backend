# Third-Party Software Notices and Information

This project incorporates components, architectural patterns, and low-level algorithms from the following open-source projects under permissible licenses:

---

## 1. fastutil

- **Project**: [vigna/fastutil](https://github.com/vigna/fastutil)
- **Copyright**: Copyright (C) 2002-2024 Sebastiano Vigna
- **License**: Apache License, Version 2.0
- **Notice**:
  - Hash mixing function `HashCommon.mix(long)` using the 64-bit golden-ratio multiplier (`0x9E3779B97F4A7C15L`) and shift-xor operations.
  - Open-addressing table indexing, flat array slot mapping, and dirty touched slot tracking patterns.
  - Applied in `com.easysubway.route.application.service.PrimitiveLongObjectMap` and `com.easysubway.route.application.service.BitPackedOdFareStore`.

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/
```

---

## 2. nigiri

- **Project**: [motis-project/nigiri](https://github.com/motis-project/nigiri)
- **Copyright**: Copyright (c) 2022 Felix Gündling / MOTIS Project
- **License**: MIT License
- **Notice**:
  - Timetable flat contiguous memory layout and static integer indexing for transit entities.
  - 64-bit service day bitfield masking and active day presence testing without locks.
  - 64-bit word packing matrix design for origin-destination transit metrics.
  - Applied in `com.easysubway.route.application.service.RouteTimetableRaptorPlanner` and `com.easysubway.route.application.service.BitPackedOdFareStore`.

```
MIT License

Copyright (c) 2022 Felix Gündling / MOTIS Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. Guava (Google Core Libraries for Java)

- **Project**: [google/guava](https://github.com/google/guava)
- **Copyright**: Copyright (C) 2011 The Guava Authors
- **License**: Apache License, Version 2.0
- **Notice**:
  - In-memory bounded cache structure (`CacheBuilder`) and concurrent map view with strict capacity bounds.
  - Applied in `com.easysubway.realtime.application.RealtimeGatewayService`.

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/
```
