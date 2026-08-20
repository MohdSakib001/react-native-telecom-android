module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        // Named rather than discovered. Autolinking finds the package class by
        // pattern-matching source, and a package that fails that scan links
        // cleanly and then does nothing at runtime — the worst kind of failure.
        packageImportPath: 'import com.telecomandroid.TelecomAndroidPackage;',
        packageInstance: 'new TelecomAndroidPackage()',
      },
      // Android-only by design. iOS CallKit is well served elsewhere.
      ios: null,
    },
  },
};
