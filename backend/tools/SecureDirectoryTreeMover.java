import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public final class SecureDirectoryTreeMover {
  private SecureDirectoryTreeMover() {}

  public static void main(String[] arguments) {
    try {
      if (arguments.length < 3 || arguments.length > 4) throw new IOException("invalid secure directory move arguments");
      move(Path.of(arguments[0]), arguments[1], Path.of(arguments[2]), arguments.length == 4 && arguments[3].equals("--test-force-unsupported"));
    } catch (IOException | RuntimeException error) {
      System.err.println(error.getMessage());
      System.exit(1);
    }
  }

  static void move(Path repositoryRoot, String temporaryName, Path outputRelative, boolean forceUnsupported) throws IOException {
    if (!isSingleName(Path.of(temporaryName)) || !isSafeRelative(outputRelative)) throw new IOException("invalid secure directory move path");
    try (DirectoryStream<Path> openedRoot = Files.newDirectoryStream(repositoryRoot)) {
      SecureDirectoryStream<Path> repository = secure(openedRoot, forceUnsupported);
      List<SecureDirectoryStream<Path>> openedParents = new ArrayList<>();
      try {
        SecureDirectoryStream<Path> backend = childDirectory(repository, Path.of("backend"), forceUnsupported);
        openedParents.add(backend);
        SecureDirectoryStream<Path> build = childDirectory(backend, Path.of("build"), forceUnsupported);
        openedParents.add(build);
        SecureDirectoryStream<Path> parent = build;
        for (Path segment : outputRelative.getParent() == null ? List.<Path>of() : outputRelative.getParent()) {
          parent = childDirectory(parent, segment, forceUnsupported);
          openedParents.add(parent);
        }
        Path outputName = outputRelative.getFileName();
        if (exists(parent, outputName)) throw new IOException("final output must be absent");
        build.move(Path.of(temporaryName), parent, outputName);
      } finally {
        for (int index = openedParents.size() - 1; index >= 0; index--) openedParents.get(index).close();
      }
    }
  }

  private static SecureDirectoryStream<Path> childDirectory(SecureDirectoryStream<Path> parent, Path name, boolean forceUnsupported) throws IOException {
    BasicFileAttributes attributes = attributes(parent, name);
    if (!attributes.isDirectory()) throw new IOException("output must not have a symlink or non-directory ancestor");
    return secure(parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS), forceUnsupported);
  }

  private static SecureDirectoryStream<Path> secure(DirectoryStream<Path> stream, boolean forceUnsupported) throws IOException {
    if (forceUnsupported || !(stream instanceof SecureDirectoryStream<?>)) {
      stream.close();
      throw new IOException("secure directory stream unavailable");
    }
    @SuppressWarnings("unchecked")
    SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
    return secure;
  }

  private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
    try {
      return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
    } catch (NoSuchFileException error) {
      throw new IOException("output parent must already exist", error);
    }
  }

  private static boolean exists(SecureDirectoryStream<Path> directory, Path name) throws IOException {
    try {
      directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
      return true;
    } catch (NoSuchFileException ignored) {
      return false;
    }
  }

  private static boolean isSingleName(Path value) {
    return value.getNameCount() == 1 && !value.isAbsolute() && !value.toString().equals(".") && !value.toString().equals("..");
  }

  private static boolean isSafeRelative(Path value) {
    if (value.isAbsolute() || value.getNameCount() == 0) return false;
    for (Path segment : value) if (segment.toString().equals(".") || segment.toString().equals("..")) return false;
    return true;
  }
}
