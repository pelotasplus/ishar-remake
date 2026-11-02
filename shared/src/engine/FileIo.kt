package dev.pelotasplus.ishar.engine


/**
 * The scripts' DOS file calls (statements 70h..78h, `637f..649a`)
 * on one handle, like the game (`ss:0bbe`). Writes go to the [GameFiles] overlay.
 */
class FileIo(private val vm: Scheduler) {
    private val m = vm.machine
    private val SS = m.ss
    private val x = vm.expr

    private class Handle(val name: String, data: ByteArray) {
        var data: ByteArray = data
        var pos = 0
        var dirty = false
    }

    private var handle: Handle? = null

    fun install() {
        val t = vm.stmts
        t[0x70] = ::fopen; t[0x71] = ::fileClose; t[0x72] = ::fcreate; t[0x73] = ::fdelete
        t[0x74] = ::freadWord; t[0x75] = ::fwriteWord; t[0x76] = ::fwriteString
        t[0x77] = ::freadBlock; t[0x78] = ::fwriteBlock
    }

    private fun fileOpen(name: String, mode: Int) {
        if (m.build == GameBuild.ISHAR3) vm.files.importFrom(Ishar3Start.importFrom(m))
        val exists = vm.files.exists(name)
        if (((mode and 0x200) != 0 && !exists) || ((mode and 0x400) != 0 && exists)) {
            handle = Handle(name, ByteArray(0)).also { it.dirty = true }
            return
        }
        if (!exists) throw UnsupportedEngineOperationException("script error 1: cannot open $name")
        val h = Handle(name, vm.files.read(name))
        if ((mode and 0x300) == 0 && GameFiles.normalizeName(name).endsWith(".sav")) {
            vm.files.onSaveLoaded?.invoke(GameFiles.normalizeName(name))
        }
        if ((mode and 0x800) != 0) h.pos = h.data.size
        handle = h
    }

    private fun fileClose() {
        val h = handle ?: throw UnsupportedEngineOperationException("script error 0eh: close without a file")
        if (h.dirty) vm.files.write(h.name, h.data)
        handle = null
    }

    private fun current(): Handle = handle ?: throw UnsupportedEngineOperationException("file call without a file")

    private fun fileRead(seg: Int, ofs: Int, n: Int): Int {
        val h = current()
        val got = (h.data.size - h.pos).coerceIn(0, n)
        for (i in 0 until got) m.wb(seg, ofs + i, h.data[h.pos + i].toInt())
        h.pos += got
        return got
    }

    private fun fileWrite(seg: Int, ofs: Int, n: Int) {
        val h = current()
        if (h.pos + n > h.data.size) h.data = h.data.copyOf(h.pos + n)
        for (i in 0 until n) h.data[h.pos + i] = m.rb(seg, ofs + i).toByte()
        h.pos += n
        h.dirty = true
    }

    private fun inlineName(): String {
        val name = x.cstr(m.ds, m.si)
        while (m.lodsb() != 0) { /* skip the name */ }
        return name
    }

    /** `70h`: an inline name and mode word, or (ffh) a string term and a mode term. */
    private fun fopen() {
        val name: String
        if (m.rb(m.ds, m.si) == 0xFF) {
            m.si = (m.si + 1) and 0xFFFF
            x.swapStrings()
            x.step()
            m.ax = m.dx
            name = x.cstr(SS, m.g(0x0C58))
        } else {
            name = inlineName()
            m.lodsw()
        }
        fileOpen(name, m.ax)
    }

    private fun fcreate() {
        val name = inlineName()
        m.lodsw()
        handle = Handle(name, ByteArray(0)).also { it.dirty = true }
        fileClose()
    }

    private fun fdelete() {
        val name = inlineName()
        if (!vm.files.delete(name)) throw UnsupportedEngineOperationException("script error 9: cannot delete $name")
    }

    private fun swap(v: Int): Int = ((v ushr 8) or ((v and 0xFF) shl 8)) and 0xFFFF

    private fun freadWord() {
        m.ax = fileRead(SS, 0x2480, 2)
        m.dx = swap(m.g(0x2480))
        x.store()
    }

    private fun fwriteWord() {
        x.step()
        m.dx = swap(m.dx)
        m.sg(0x2480, m.dx)
        fileWrite(SS, 0x2480, 2)
    }

    private fun fwriteString() {
        val start = m.si
        while (m.lodsb() != 0) { /* to the NUL */ }
        fileWrite(m.ds, start, (m.si - start) and 0xFFFF)
    }

    /** A local (or, after a 0 word, a `main.io` global) and a byte count. */
    private fun blockArgs(): Pair<Int, Int> {
        var di = m.bp
        var ax = m.lodsw()
        if (ax == 0) {
            di = m.g(0x0BF6)
            ax = m.lodsw()
        }
        val n = m.lodsw()
        if (m.build == GameBuild.ISHAR3) m.lodsw() // its counts are 32-bit (start.exe 8915 lodsd)
        val at = (ax + di) and 0xFFFF
        return (m.pointerArrays[(m.es shl 4) + at] ?: at) to n
    }

    private fun swapWords(ofs: Int, n: Int) {
        for (i in 0 until n) {
            val a = ofs + 2 * i
            m.ww(m.es, a, swap(m.rw(m.es, a)))
        }
    }

    private fun freadBlock() {
        val (dx, n) = blockArgs()
        val got = fileRead(m.es, dx, n)
        m.ax = got
        if (m.rb(m.es, dx - 2) == 2) swapWords(dx, got ushr 1) // a word array: big-endian on disk
    }

    private fun fwriteBlock() {
        val (dx, n) = blockArgs()
        val word = m.rb(m.es, dx - 2) == 2
        if (word) swapWords(dx, n ushr 1)
        fileWrite(m.es, dx, n)
        if (word) swapWords(dx, n ushr 1)
    }
}
