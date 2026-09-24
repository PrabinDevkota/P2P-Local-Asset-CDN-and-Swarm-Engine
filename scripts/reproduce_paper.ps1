# Recreate the B0 smoke table from a fresh checkout (blueprint P10-04).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& .\mvnw.cmd -q -pl benchmark-runner -am org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.prabin.swarmedge.benchmark.ReproducePaper" "-Dexec.classpathScope=compile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
