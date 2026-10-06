import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;

/*
 * =====================================================================================
 *  MYERS DIFF  -  Java implementation
 * =====================================================================================
 *
 *  USAGE
 *      java Main lines     FILE_A FILE_B   -> minimal line diff
 *      java Main highlight FILE_A FILE_B   -> line diff + changed-character ranges
 *
 *  OUTPUT FORMAT (stdout only; errors go to stderr)
 *      " " + line   the line exists in both files
 *      "-" + line   the line exists only in A (deleted)
 *      "+" + line   the line exists only in B (inserted)
 *      "? old | new"   (highlight command only) printed right after a paired "+" line,
 *                      gives the changed character ranges of the old and the new line.
 *
 *  THE BIG PICTURE (what happens when the program runs)
 *
 *      1. Read both files as raw bytes.
 *      2. Split them into lines (on the byte '\n').
 *      3. Give every distinct line a small integer id, so that comparing two lines
 *         becomes comparing two ints (fast).
 *      4. Throw away lines that occur in only one of the files (they can never match,
 *         so they are always deletions/insertions). This is only a speed-up.
 *      5. Run MYERS on the remaining id sequences. The result is an "edit script":
 *         a list of KEEP / DELETE / INSERT operations. It is minimal.
 *      6. Put the thrown-away lines back into the edit script.
 *      7. Print the script. For "highlight", every paired -/+ line additionally gets a
 *         character-level diff, which uses THE SAME Myers code, but on Unicode
 *         code points instead of line ids.
 *
 *  WHY ONE MYERS FOR BOTH PARTS?
 *      Myers only needs two int[] sequences and the question "are element i of A and
 *      element j of B equal?". Part A feeds it line ids, Part B feeds it code points.
 *
 *  WHY THE "LINEAR SPACE" VERSION OF MYERS?
 *      The simplest Myers variant remembers the whole search history to rebuild the
 *      answer, which needs memory proportional to D*D (D = number of differences).
 *      For big D that does not fit into memory. The linear-space variant used here
 *      needs memory proportional to the input size only, while still taking O(N*D) time.
 */
public class Main {

    // ---------------------------------------------------------------------------------
    // The three kinds of edit operations. A diff result is simply a byte[] made of these.
    // ---------------------------------------------------------------------------------
    static final byte KEEP = 0;     // element exists in both sequences (unchanged)
    static final byte DELETE = 1;   // element exists only in A (must be removed)
    static final byte INSERT = 2;   // element exists only in B (must be added)

    // =================================================================================
    //  main()
    // =================================================================================
    public static void main(String[] args) {

        // ---- 1. Check the command line: exactly "<command> <fileA> <fileB>". ----
        // On any mistake: print usage to stderr, print nothing to stdout, exit code 2.
        if (args.length != 3 || !(args[0].equals("lines") || args[0].equals("highlight"))) {
            System.err.println("Usage: java Main lines|highlight FILE_A FILE_B");
            System.exit(2);
        }
        boolean highlight = args[0].equals("highlight");

        // ---- 2. Read BOTH files completely BEFORE printing anything. ----
        // If a file cannot be read we must have printed nothing to stdout yet.
        // Files.readAllBytes gives raw bytes: no charset decoding, no CRLF conversion.
        byte[] bytesA;
        byte[] bytesB;
        try {
            bytesA = Files.readAllBytes(Paths.get(args[1]));
            bytesB = Files.readAllBytes(Paths.get(args[2]));
        } catch (IOException | RuntimeException e) {
            // IOException: file missing / unreadable.
            // RuntimeException: e.g. InvalidPathException for a malformed path.
            System.err.println("Error: cannot read input file: " + e.getMessage());
            System.exit(2);
            return; // never reached, but tells the compiler bytesA/bytesB are assigned
        }

        // ---- 3. Split the raw bytes into lines. ----
        Lines linesA = splitLines(bytesA);
        Lines linesB = splitLines(bytesB);

        // ---- 4. Replace each line by an int id (equal bytes -> equal id). ----
        // lineIds returns three things: ids of A, ids of B, and the count of distinct ids.
        int[][] ids = lineIds(linesA, linesB);

        // ---- 5. Compute the minimal edit script (KEEP/DELETE/INSERT per element). ----
        byte[] ops = diffWithoutUniqueLines(ids[0], ids[1], ids[2][0]);

        // ---- 6. Print the result. Output goes through a buffer for speed and is
        //         written as raw bytes, so lines with invalid UTF-8 stay untouched. ----
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
            printDiff(linesA, linesB, ops, highlight, out);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error: cannot write output: " + e.getMessage());
            System.exit(2);
        }
    }

    // =================================================================================
    //  LINES: splitting a file into lines without copying
    // =================================================================================

    /**
     * A file split into lines. Instead of creating one byte[] per line (which would
     * create hundreds of thousands of small objects), we remember only WHERE each line
     * starts and ends inside the one big byte[] of the file.
     *
     * Example for the file bytes  "ab\ncd\n":
     *     data  = [a, b, \n, c, d, \n]
     *     start = [0, 3]      end = [2, 5]      count = 2
     *     line 0 = data[0 .. 2) = "ab"          line 1 = data[3 .. 5) = "cd"
     * The '\n' itself is NOT part of a line (end points at it). A '\r' before it, however,
     * IS part of the line (we never remove it).
     */
    static final class Lines {
        byte[] data;   // the whole file content
        int[] start;   // start[i] = index of the first byte of line i
        int[] end;     // end[i]   = index one PAST the last byte of line i
        int count;     // number of lines

        /** Number of bytes in line i (without the newline). */
        int length(int i) {
            return end[i] - start[i];
        }
    }

    /**
     * Splits raw file bytes into lines following the assignment rules:
     *   1. split on the byte '\n'
     *   2. if the last piece is empty, drop it
     *   3. keep '\r' as part of the line
     *
     * Examples:
     *   ""        -> []            (no lines)
     *   "\n"      -> [""]          (one empty line)
     *   "a"       -> ["a"]
     *   "a\n"     -> ["a"]         (final empty piece dropped)
     *   "a\n\nb"  -> ["a","","b"]
     *   "a\r\nb"  -> ["a\r","b"]
     */
    static Lines splitLines(byte[] data) {
        // First pass: count the lines so the arrays can be allocated at the right size.
        // Every '\n' ends one line.
        int count = 0;
        for (byte b : data) {
            if (b == '\n') {
                count++;
            }
        }
        // If the file does not end with '\n' there is one more (unterminated) last line.
        // (If it DOES end with '\n', the empty piece after it is simply not counted -
        //  that is rule 2 "drop the empty final piece".)
        if (data.length > 0 && data[data.length - 1] != '\n') {
            count++;
        }

        Lines lines = new Lines();
        lines.data = data;
        lines.start = new int[count];
        lines.end = new int[count];
        lines.count = count;

        // Second pass: record start/end of every line.
        int line = 0;   // index of the line currently being filled
        int start = 0;  // where the current line begins
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines.start[line] = start;
                lines.end[line] = i;   // line is data[start .. i), '\n' excluded
                line++;
                start = i + 1;         // next line begins after the '\n'
            }
        }
        // Leftover bytes after the last '\n' form the final line (no trailing newline).
        if (start < data.length) {
            lines.start[line] = start;
            lines.end[line] = data.length;
        }
        return lines;
    }

    // =================================================================================
    //  LINE IDS: turning byte lines into ints
    // =================================================================================

    /**
     * A key for the HashMap that represents one line by its bytes.
     *
     * Why is this class needed? A plain byte[] in Java compares by REFERENCE (two arrays
     * with the same content are "different"), and has an identity hash code. A HashMap
     * needs value-based equals/hashCode, so we wrap the byte range and implement them.
     * The wrapper only points into the file bytes (no copy).
     */
    static final class ByteLine {
        final byte[] data;  // the file bytes
        final int from;     // line start (inclusive)
        final int to;       // line end (exclusive)
        final int hash;     // hash code, computed once in the constructor

        ByteLine(byte[] data, int from, int to) {
            this.data = data;
            this.from = from;
            this.to = to;
            // Standard polynomial hash over the bytes of the line.
            int h = 1;
            for (int i = from; i < to; i++) {
                h = 31 * h + data[i];
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        /** Two ByteLines are equal when their byte ranges have identical content. */
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ByteLine)) {
                return false;
            }
            ByteLine o = (ByteLine) other;
            // Arrays.equals with ranges compares data[from..to) with o.data[o.from..o.to):
            // different lengths -> false; same length -> byte-by-byte comparison.
            return Arrays.equals(data, from, to, o.data, o.from, o.to);
        }
    }

    /**
     * Assigns an integer id to every distinct line of BOTH files, using one shared map,
     * so that a line of A and an identical line of B get the SAME id.
     *
     * Example: A = [x, y, x]   B = [y, z]   ->   ids: x=0, y=1, z=2
     *          idsA = [0, 1, 0]   idsB = [1, 2]   distinctCount = 3
     *
     * After this, "line i of A equals line j of B" is just "idsA[i] == idsB[j]".
     * Returns { idsA, idsB, { distinctCount } }.
     */
    static int[][] lineIds(Lines a, Lines b) {
        HashMap<ByteLine, Integer> idOf = new HashMap<>();
        int[] idsA = new int[a.count];
        int[] idsB = new int[b.count];
        for (int i = 0; i < a.count; i++) {
            idsA[i] = idFor(idOf, new ByteLine(a.data, a.start[i], a.end[i]));
        }
        for (int i = 0; i < b.count; i++) {
            idsB[i] = idFor(idOf, new ByteLine(b.data, b.start[i], b.end[i]));
        }
        return new int[][] {idsA, idsB, {idOf.size()}};
    }

    /** Returns the id of this line; creates a new id (0, 1, 2, ...) if it is new. */
    static int idFor(HashMap<ByteLine, Integer> idOf, ByteLine key) {
        Integer id = idOf.get(key);
        if (id == null) {
            id = idOf.size();      // next free id
            idOf.put(key, id);
        }
        return id;
    }

    // =================================================================================
    //  SPEED-UP: remove lines that exist in only one file
    // =================================================================================

    /**
     * Computes the minimal edit script for two id sequences, with a pre-processing step.
     *
     * IDEA: A line that never occurs in B can never be KEEP (it has no partner), so in
     * EVERY edit script it is a DELETE. Likewise a line that never occurs in A is always
     * an INSERT. Removing such lines first does not change the minimal number of edits,
     * but it can shrink the problem a lot (in the extreme case "every line changed" it
     * shrinks it to nothing). We then run Myers on the rest and put the removed lines
     * back as DELETE/INSERT operations at their original positions.
     *
     * @param a        line ids of file A
     * @param b        line ids of file B
     * @param idCount  number of distinct ids (ids are 0 .. idCount-1)
     * @return the full edit script for a -> b
     */
    static byte[] diffWithoutUniqueLines(int[] a, int[] b, int idCount) {

        // countA[id] = how often this id occurs in A; countB[id] likewise for B.
        int[] countA = new int[idCount];
        int[] countB = new int[idCount];
        for (int v : a) {
            countA[v]++;
        }
        for (int v : b) {
            countB[v]++;
        }

        // Build filteredA / filteredB: only elements that ALSO occur in the other file.
        // (First count how many survive, then allocate exactly that much.)
        int keptA = 0;
        for (int v : a) {
            if (countB[v] > 0) {
                keptA++;
            }
        }
        int keptB = 0;
        for (int v : b) {
            if (countA[v] > 0) {
                keptB++;
            }
        }
        int[] filteredA = new int[keptA];
        int[] filteredB = new int[keptB];
        int p = 0;
        for (int v : a) {
            if (countB[v] > 0) {
                filteredA[p++] = v;
            }
        }
        p = 0;
        for (int v : b) {
            if (countA[v] > 0) {
                filteredB[p++] = v;
            }
        }

        // The actual Myers diff on the reduced sequences.
        byte[] small = myersDiff(filteredA, filteredB);

        // ---- Merge: put the removed elements back. ----
        // i walks through the ORIGINAL a, j through the ORIGINAL b. Before handling each
        // operation of the reduced script, we first emit all removed elements that stand
        // at the current positions: removed A elements become DELETE, removed B elements
        // become INSERT. Then we handle the operation itself and advance i / j.
        byte[] ops = new byte[a.length + b.length];  // upper bound of the script length
        int size = 0;
        int i = 0;
        int j = 0;
        for (byte op : small) {
            while (i < a.length && countB[a[i]] == 0) {   // a[i] has no partner in B
                ops[size++] = DELETE;
                i++;
            }
            while (j < b.length && countA[b[j]] == 0) {   // b[j] has no partner in A
                ops[size++] = INSERT;
                j++;
            }
            ops[size++] = op;
            if (op == KEEP) {          // consumes one element of each sequence
                i++;
                j++;
            } else if (op == DELETE) { // consumes one element of A
                i++;
            } else {                   // INSERT consumes one element of B
                j++;
            }
        }
        // Everything left over (after the last reduced operation) was removed.
        while (i < a.length) {
            ops[size++] = DELETE;
            i++;
        }
        while (j < b.length) {
            ops[size++] = INSERT;
            j++;
        }
        return Arrays.copyOf(ops, size);   // cut the array to its real length
    }

    // =================================================================================
    //  MYERS
    // =================================================================================

    /**
     * Public entry point of the diff core.
     * Returns the minimal edit script (KEEP / DELETE / INSERT, in forward order) that
     * turns sequence a into sequence b. "Minimal" = fewest DELETE + INSERT operations.
     * Works for any int sequences (line ids in Part A, code points in Part B).
     */
    static byte[] myersDiff(int[] a, int[] b) {
        Myers myers = new Myers(a, b);
        myers.diff(0, a.length, 0, b.length);   // diff the complete sequences
        return Arrays.copyOf(myers.ops, myers.size);
    }

    /**
     * ---------------------------------------------------------------------------------
     *  Myers' O(ND) algorithm (linear-space version)
     * ---------------------------------------------------------------------------------
     *
     *  THE EDIT GRAPH
     *  Imagine a grid. The x axis walks along sequence A (0..N), the y axis along
     *  sequence B (0..M). We start at (0,0) and want to reach (N,M).
     *     - move RIGHT  (x+1)       = DELETE  a[x]      (costs 1 edit)
     *     - move DOWN   (y+1)       = INSERT  b[y]      (costs 1 edit)
     *     - move DIAGONAL (x+1,y+1) = KEEP    (free, only allowed if a[x] == b[y])
     *  A cheapest path from (0,0) to (N,M) is a minimal edit script.
     *
     *  DIAGONALS
     *  Every point (x,y) lies on diagonal k = x - y. A RIGHT move increases k by 1,
     *  a DOWN move decreases k by 1, a free diagonal move keeps k.
     *
     *  SNAKES
     *  A "snake" is a run of free diagonal moves (a stretch where A and B match).
     *
     *  THE CORE IDEA OF MYERS
     *  For every number of edits d = 0, 1, 2, ... and every diagonal k, remember only the
     *  FURTHEST x that can be reached on diagonal k using exactly d edits:
     *          V[k] = furthest x on diagonal k      (then y = x - k)
     *  To get V[k] for d edits, start from the better one of the two neighbours of the
     *  previous round (d-1 edits):
     *          from diagonal k+1 by a DOWN move  -> x stays the same
     *          from diagonal k-1 by a RIGHT move -> x grows by 1
     *  take the one with the larger x, then slide along the snake while elements match.
     *  The first d for which we reach (N,M) is the minimal number of edits D.
     *  Time is O(N*D), because for each d only d+1 diagonals are looked at.
     *
     *  THE LINEAR-SPACE TRICK (middle snake)
     *  Remembering every V of every round would need O(D*D) memory. Instead we search
     *  from BOTH ends at once:
     *     - the FORWARD search starts at (0,0)  and walks towards (N,M),
     *     - the BACKWARD search starts at (N,M) and walks towards (0,0)
     *       (this is the same algorithm run on the reversed sequences).
     *  After about D/2 rounds each, the two searches touch each other. The place where
     *  they touch is a snake that lies on a minimal path: the "middle snake". Because it
     *  is on a minimal path, we can solve "(0,0) -> start of the snake" and
     *  "end of the snake -> (N,M)" independently by recursion. Each half needs only about
     *  D/2 edits, so the recursion is shallow (about log2(D) levels) and we never need to
     *  store a history. The pieces of the answer are produced directly in forward order.
     */
    static final class Myers {

        /** Marker value for "this diagonal has no usable point". Very negative on purpose. */
        static final int DEAD = Integer.MIN_VALUE / 2;

        final int[] a;         // sequence A (e.g. line ids of file A)
        final int[] b;         // sequence B
        final byte[] ops;      // the result: edit operations, written in forward order
        int size = 0;          // how many operations have been written to ops so far

        // The two "V" arrays. vf[k] belongs to the forward search, vb[k] to the backward
        // search. They are allocated ONCE and reused by all recursive calls.
        final int[] vf;
        final int[] vb;

        // k can be negative but Java array indexes cannot, so we index with k + offset.
        final int offset;

        // Output of middleSnake(): the snake that was found, as coordinates RELATIVE to
        // the start of the sub-problem that was searched.
        int snakeStartX, snakeStartY;   // first point of the snake
        int snakeEndX, snakeEndY;       // last point of the snake (after sliding)

        Myers(int[] a, int[] b) {
            this.a = a;
            this.b = b;
            // A script never has more than N + M operations (every element is KEEP,
            // DELETE or INSERT at most once).
            this.ops = new byte[a.length + b.length];
            // Each search needs at most ceil((N+M)/2) rounds, so diagonals only range
            // from -max to +max. We need a little slack (+1 / +3) because the code reads
            // the neighbours k-1 and k+1.
            int max = (a.length + b.length + 1) / 2;
            this.offset = max + 1;
            this.vf = new int[2 * max + 3];
            this.vb = new int[2 * max + 3];
        }

        /** Appends 'count' copies of the operation 'op' to the result. */
        void emit(byte op, int count) {
            for (int i = 0; i < count; i++) {
                ops[size++] = op;
            }
        }

        /**
         * Computes the minimal edit script for the sub-problem
         *        a[aLo .. aHi)   versus   b[bLo .. bHi)
         * and APPENDS it to ops (so the pieces end up in the correct forward order).
         *
         * Structure:
         *   1. strip the common beginning (-> KEEP)  and the common end (-> KEEP later)
         *   2. if one side is now empty, the rest is a plain DELETE-all or INSERT-all
         *   3. otherwise find the middle snake and recurse on the part before it and the
         *      part after it
         */
        void diff(int aLo, int aHi, int bLo, int bHi) {

            // ---- 1a. Common PREFIX: equal elements at the start are always KEEP. ----
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                emit(KEEP, 1);
                aLo++;
                bLo++;
            }

            // ---- 1b. Common SUFFIX: equal elements at the end are always KEEP. ----
            // We shrink the range now, but the KEEPs must be written AFTER the middle
            // part (that is where they belong), so we only count them here.
            int suffix = 0;
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
                aHi--;
                bHi--;
                suffix++;
            }

            if (aLo == aHi) {
                // ---- 2a. A is used up: everything left in B must be inserted. ----
                emit(INSERT, bHi - bLo);
            } else if (bLo == bHi) {
                // ---- 2b. B is used up: everything left in A must be deleted. ----
                emit(DELETE, aHi - aLo);
            } else {
                // ---- 3. Both sides non-empty: divide and conquer. ----
                middleSnake(aLo, aHi, bLo, bHi);

                // IMPORTANT: copy the snake into local variables first. The recursive
                // calls below call middleSnake again, which would overwrite the fields
                // snakeStartX... before we have used them for the second half.
                int startX = snakeStartX;
                int startY = snakeStartY;
                int endX = snakeEndX;
                int endY = snakeEndY;

                // Part BEFORE the snake: from the start of this range to the snake start.
                diff(aLo, aLo + startX, bLo, bLo + startY);
                // The snake itself: matching elements, i.e. KEEP.
                emit(KEEP, endX - startX);
                // Part AFTER the snake: from the snake end to the end of this range.
                diff(aLo + endX, aHi, bLo + endY, bHi);
            }

            // ---- The common suffix found in step 1b, now in the correct position. ----
            emit(KEEP, suffix);
        }

        /**
         * Finds the middle snake of the sub-problem a[aLo..aHi) vs b[bLo..bHi).
         *
         * It runs the forward and the backward search in turns (round d: first forward,
         * then backward) and after every step checks whether the two searches overlap.
         * When they do, the snake of the path that was just extended is the middle snake.
         * Result is stored in snakeStartX/Y and snakeEndX/Y (relative to aLo / bLo).
         *
         * Local coordinates used below: n = length of the A part, m = length of the B
         * part. Point (x, y) means "x elements of A and y elements of B are consumed".
         * a[aLo + x] is the element at A-position x.
         */
        void middleSnake(int aLo, int aHi, int bLo, int bHi) {
            int n = aHi - aLo;
            int m = bHi - bLo;

            // The end point (n, m) lies on diagonal delta = n - m.
            int delta = n - m;

            // The total number of edits D is minimal when the searches meet. If delta is
            // odd, D is odd, which means the meeting happens during a FORWARD step (the
            // forward search has done one edit more than the backward one). If delta is
            // even, D is even and the meeting is noticed during a BACKWARD step.
            boolean odd = (delta & 1) != 0;

            // Each search needs at most ceil((n+m)/2) rounds.
            int max = (n + m + 1) / 2;

            // Starting trick: "pretend" diagonal 1 already reached x = 0. In round d = 0,
            // diagonal k = 0 then starts from x = vf[1] = 0, i.e. at the origin. The same
            // for the backward search (which starts at the other corner).
            vf[offset + 1] = 0;
            vb[offset + 1] = 0;

            for (int d = 0; d <= max; d++) {

                // =============== FORWARD SEARCH, round d ===============
                // After d edits only diagonals -d, -d+2, ..., d are reachable
                // (every edit changes k by exactly 1, so k always has the parity of d).
                for (int k = -d; k <= d; k += 2) {

                    int x;
                    // Choose where we came from:
                    //  - k == -d : lowest diagonal, only k+1 exists (previous round).
                    //  - k != d and vf[k-1] < vf[k+1] : the k+1 neighbour got further.
                    //  Otherwise come from k-1.
                    if (k == -d || (k != d && vf[offset + k - 1] < vf[offset + k + 1])) {
                        x = vf[offset + k + 1];        // came from k+1: DOWN move, x unchanged
                    } else {
                        x = vf[offset + k - 1] + 1;    // came from k-1: RIGHT move, x + 1
                    }
                    int y = x - k;                     // since k = x - y

                    // A point outside the grid (x > n, y > m, or derived from a DEAD
                    // diagonal) is useless: no path to (n, m) can pass through it.
                    // Mark the diagonal DEAD so later rounds never prefer it.
                    if (x < 0 || x > n || y < 0 || y > m) {
                        vf[offset + k] = DEAD;
                        continue;
                    }

                    // Remember where the snake starts (needed if this becomes the answer).
                    int startX = x;
                    int startY = y;

                    // Slide along the snake: free diagonal moves while elements are equal.
                    while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                        x++;
                        y++;
                    }

                    vf[offset + k] = x;   // furthest x on diagonal k with d edits

                    // Do the searches overlap? Only possible here when delta is odd.
                    // The backward search has done d-1 rounds so far; it has data for
                    // its diagonals -(d-1)..(d-1). The forward diagonal k corresponds to
                    // backward diagonal (delta - k), so that must be within that range.
                    // The backward search measures distance FROM THE END, so the two
                    // paths cross when  forward x  +  backward x  >=  n.
                    if (odd && k >= delta - (d - 1) && k <= delta + (d - 1)
                            && x + vb[offset + delta - k] >= n) {
                        snakeStartX = startX;
                        snakeStartY = startY;
                        snakeEndX = x;
                        snakeEndY = y;
                        return;   // middle snake found
                    }
                }

                // =============== BACKWARD SEARCH, round d ===============
                // Same algorithm, but on the reversed sequences: "x" now counts elements
                // consumed FROM THE END of A, "y" from the end of B. So the element at
                // reversed position x is a[aHi - 1 - x] and b[bHi - 1 - y].
                for (int k = -d; k <= d; k += 2) {

                    int x;
                    if (k == -d || (k != d && vb[offset + k - 1] < vb[offset + k + 1])) {
                        x = vb[offset + k + 1];
                    } else {
                        x = vb[offset + k - 1] + 1;
                    }
                    int y = x - k;

                    if (x < 0 || x > n || y < 0 || y > m) {
                        vb[offset + k] = DEAD;
                        continue;
                    }

                    int startX = x;
                    int startY = y;

                    while (x < n && y < m && a[aHi - 1 - x] == b[bHi - 1 - y]) {
                        x++;
                        y++;
                    }

                    vb[offset + k] = x;

                    // Overlap check, only possible here when delta is even. The forward
                    // search has completed d rounds (diagonals -d..d); backward diagonal
                    // k corresponds to forward diagonal (delta - k).
                    if (!odd && k >= delta - d && k <= delta + d
                            && x + vf[offset + delta - k] >= n) {
                        // The snake was found in REVERSED coordinates; convert back to
                        // normal coordinates:  normal = length - reversed.
                        // Reversed walk went startX -> x, i.e. in normal direction the
                        // snake goes from (n - x, m - y) to (n - startX, m - startY).
                        snakeStartX = n - x;
                        snakeStartY = m - y;
                        snakeEndX = n - startX;
                        snakeEndY = m - startY;
                        return;   // middle snake found
                    }
                }
            }

            // Cannot happen: the searches always meet within 'max' rounds.
            throw new IllegalStateException("no middle snake found");
        }
    }

    // =================================================================================
    //  OUTPUT: printing the edit script (Part A and Part B)
    // =================================================================================

    /**
     * Prints the diff in the required format.
     *
     * The edit script is a sequence like  K K D D I I K D I K ...
     * A "change block" is a maximal run of non-KEEP operations (D and I mixed), e.g.
     * "D I D I" or "D D I". Myers may mix D and I inside a block in any order, but the
     * assignment wants ALL "-" lines first, then ALL "+" lines. That is easy: inside a
     * block the deleted lines are consecutive lines of A and the inserted lines are
     * consecutive lines of B, so we only need
     *      delStart, delCount   (which lines of A are deleted)
     *      insStart, insCount   (which lines of B are inserted)
     * and print the deletions first, then the insertions. The order inside the block
     * produced by Myers therefore does not matter.
     *
     * For "highlight": the t-th inserted line is PAIRED with the t-th deleted line (as
     * long as t < delCount). Right after such a paired "+" line a "?" line is printed.
     * Inserted lines beyond delCount, and deleted lines beyond insCount, are unpaired
     * and get no "?" line.
     */
    static void printDiff(Lines a, Lines b, byte[] ops, boolean highlight,
                          OutputStream out) throws IOException {
        int i = 0;   // index of the next unprinted line of A
        int j = 0;   // index of the next unprinted line of B
        int p = 0;   // position in the edit script
        while (p < ops.length) {

            if (ops[p] == KEEP) {
                // Unchanged line: print with a leading space. (a line i and b line j are
                // equal here, so printing the one from A is fine.)
                writeLine(out, ' ', a, i);
                i++;
                j++;
                p++;
                continue;
            }

            // A change block starts here. Walk to its end, counting how many lines of
            // A are deleted and how many lines of B are inserted.
            int delStart = i;
            int insStart = j;
            while (p < ops.length && ops[p] != KEEP) {
                if (ops[p] == DELETE) {
                    i++;
                } else {
                    j++;
                }
                p++;
            }
            int delCount = i - delStart;
            int insCount = j - insStart;

            // First ALL deletions ...
            for (int t = 0; t < delCount; t++) {
                writeLine(out, '-', a, delStart + t);
            }
            // ... then ALL insertions (each paired one followed by its "?" line).
            for (int t = 0; t < insCount; t++) {
                writeLine(out, '+', b, insStart + t);
                if (highlight && t < delCount) {
                    // pair: deleted line number t  <->  inserted line number t
                    String ranges = highlightRanges(a, delStart + t, b, insStart + t);
                    out.write(ranges.getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
            }
        }
    }

    /** Writes: prefix character, the raw bytes of line 'index', and a newline. */
    static void writeLine(OutputStream out, char prefix, Lines lines, int index) throws IOException {
        out.write(prefix);
        out.write(lines.data, lines.start[index], lines.length(index));  // raw bytes, no decoding
        out.write('\n');
    }

    // =================================================================================
    //  PART B: character-level highlighting
    // =================================================================================

    /**
     * Convenience overload: takes line number indexA of file A and indexB of file B,
     * decodes them as UTF-8 text and returns the "? ..." line.
     * (Only Part B decodes text. Bytes that are not valid UTF-8 are turned into the
     *  replacement character U+FFFD by Java; Part B inputs are expected to be valid.)
     */
    static String highlightRanges(Lines a, int indexA, Lines b, int indexB) {
        String oldText = new String(a.data, a.start[indexA], a.length(indexA), StandardCharsets.UTF_8);
        String newText = new String(b.data, b.start[indexB], b.length(indexB), StandardCharsets.UTF_8);
        return highlightRanges(oldText, newText);
    }

    /**
     * Compares two lines character by character and returns
     *         "? <old ranges> | <new ranges>"
     * where each range list names the characters that are NOT part of the common
     * subsequence, i.e. deleted from the old line / inserted into the new line.
     *
     * "Character" means Unicode CODE POINT, not Java char. Java strings are UTF-16, so
     * an emoji such as U+1F600 takes TWO chars (a surrogate pair). codePoints() gives one
     * int per real character, so an emoji correctly counts as ONE.
     *
     * Because the code points go through the same minimal Myers diff, the number of
     * highlighted characters is the smallest possible, and removing the highlighted
     * characters from both lines leaves identical text.
     */
    static String highlightRanges(String oldText, String newText) {
        int[] oldChars = oldText.codePoints().toArray();
        int[] newChars = newText.codePoints().toArray();

        // Edit script on characters (same algorithm as for lines).
        byte[] ops = myersDiff(oldChars, newChars);

        // Mark which characters are changed. oldChanged[i] = true means the i-th
        // character of the old line was deleted; newChanged[j] = true means the j-th
        // character of the new line was inserted. KEEP characters stay false.
        boolean[] oldChanged = new boolean[oldChars.length];
        boolean[] newChanged = new boolean[newChars.length];
        int i = 0;   // position in the old line
        int j = 0;   // position in the new line
        for (byte op : ops) {
            if (op == KEEP) {
                i++;
                j++;
            } else if (op == DELETE) {
                oldChanged[i++] = true;
            } else {
                newChanged[j++] = true;
            }
        }
        return "? " + buildRanges(oldChanged) + " | " + buildRanges(newChanged);
    }

    /**
     * Turns the true/false marks into the range text.
     *
     * Rules: ranges are "start-end" with END EXCLUSIVE, counted from 0, separated by
     * commas without spaces, in increasing order; touching ranges are merged; if nothing
     * is marked the result is ".".
     *
     * Example: marks at positions 2, 3, 4 and 9   ->   "2-5,9-10"
     *
     * Merging touching ranges comes for free: we always extend a range over a WHOLE run
     * of consecutive true values, so two ranges can never touch (there is always at least
     * one false between them).
     */
    static String buildRanges(boolean[] changed) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < changed.length) {
            if (!changed[i]) {          // unchanged character: skip
                i++;
                continue;
            }
            int start = i;              // a run of changed characters starts here
            while (i < changed.length && changed[i]) {
                i++;                    // extend the run as far as possible
            }
            if (sb.length() > 0) {
                sb.append(',');         // separator between ranges (no spaces)
            }
            sb.append(start).append('-').append(i);   // i is already the exclusive end
        }
        return sb.length() == 0 ? "." : sb.toString();   // "." when nothing changed
    }
}
