[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateSet('Menu', 'Gateway', 'Youtube', 'Series', 'Preferences', 'Music', 'All')]
    [string]$Start = 'Menu',

    [string]$JavaHome = $env:ATALAYA_JAVA_HOME,
    [string]$MavenSettings = $env:ATALAYA_MAVEN_SETTINGS,
    [string]$MavenOpts = $env:ATALAYA_MAVEN_OPTS
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
while (-not ((Test-Path -LiteralPath (Join-Path $projectRoot 'youtube-service\pom.xml') -PathType Leaf) -and
             (Test-Path -LiteralPath (Join-Path $projectRoot 'series-service\pom.xml') -PathType Leaf) -and
             (Test-Path -LiteralPath (Join-Path $projectRoot 'preferences-service\pom.xml') -PathType Leaf) -and
             (Test-Path -LiteralPath (Join-Path $projectRoot 'gateway\pom.xml') -PathType Leaf) -and
             (Test-Path -LiteralPath (Join-Path $projectRoot 'music-library\package.json') -PathType Leaf))) {
    $parentDirectory = Split-Path -Parent $projectRoot
    if ([string]::IsNullOrWhiteSpace($parentDirectory) -or $parentDirectory -eq $projectRoot) {
        throw "Could not locate the Atalaya repository root above '$PSScriptRoot'."
    }
    $projectRoot = $parentDirectory
}
$projectRoot = (Resolve-Path -LiteralPath $projectRoot).Path

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $HOME '.jdks\openjdk-27'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw "Java 27 was not found at '$JavaHome'. Pass -JavaHome or set ATALAYA_JAVA_HOME."
}

if ([string]::IsNullOrWhiteSpace($MavenSettings)) {
    $MavenSettings = Join-Path $HOME '.m2\settings.xml'
}
if (-not (Test-Path -LiteralPath $MavenSettings -PathType Leaf)) {
    throw "Maven settings were not found at '$MavenSettings'. Pass -MavenSettings or set ATALAYA_MAVEN_SETTINGS."
}
try {
    $settingsXml = New-Object System.Xml.XmlDocument
    $settingsXml.Load($MavenSettings)
} catch {
    throw "Maven settings XML is invalid at '$MavenSettings': $($_.Exception.Message)"
}

$maven = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
$mavenCommand = if ($maven) { $maven.Source } else { $null }
$musicRoot = Join-Path $projectRoot 'music-library'
if (-not $mavenCommand) {
    throw 'Maven was not found on PATH. Install Maven before starting the Java services.'
}
if (-not (Test-Path -LiteralPath (Join-Path $musicRoot 'package.json') -PathType Leaf)) {
    throw "Angular package.json was not found at '$musicRoot'."
}

$node = Get-Command 'node.exe' -ErrorAction SilentlyContinue
$npm = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
if (-not $node -or -not $npm) {
    throw 'Node.js and npm must be available on PATH before starting the music library.'
}
if (-not (Test-Path -LiteralPath (Join-Path $musicRoot 'node_modules'))) {
    throw "Angular dependencies are missing. Run 'npm install' once from '$musicRoot'."
}

function ConvertTo-PowerShellLiteral {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Start-AtalayaWindow {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string[]]$CommandLines
    )

    $command = @(
        "`$host.UI.RawUI.WindowTitle = $(ConvertTo-PowerShellLiteral "Atalaya - $Name")"
        "Set-Location -LiteralPath $(ConvertTo-PowerShellLiteral $WorkingDirectory)"
    ) + $CommandLines
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(($command -join "`r`n")))

    $powerShell = Get-Command 'powershell.exe' -ErrorAction SilentlyContinue
    if (-not $powerShell) {
        $powerShell = Get-Command 'pwsh.exe' -ErrorAction SilentlyContinue
    }
    if (-not $powerShell) {
        throw 'Could not find powershell.exe or pwsh.exe to open a service terminal.'
    }

    if ($PSCmdlet.ShouldProcess($Name, "Open a PowerShell window in '$WorkingDirectory'")) {
        $process = Start-Process -FilePath $powerShell.Source `
            -WorkingDirectory $WorkingDirectory `
            -ArgumentList @('-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encodedCommand) `
            -PassThru
        Write-Host "Started $Name (terminal PID $($process.Id))."
    }
}

function Start-AtalayaService {
    param(
        [Parameter(Mandatory = $true)][string]$Module,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $javaBin = Join-Path $JavaHome 'bin'
    $javaHomeLiteral = ConvertTo-PowerShellLiteral $JavaHome
    $javaBinLiteral = ConvertTo-PowerShellLiteral $javaBin
    $settingsLiteral = ConvertTo-PowerShellLiteral $MavenSettings
    $mavenOptsLiteral = ConvertTo-PowerShellLiteral $MavenOpts
    $serviceRoot = Join-Path $projectRoot $Module
    $pomLiteral = ConvertTo-PowerShellLiteral (Join-Path $serviceRoot 'pom.xml')
    $mavenLiteral = ConvertTo-PowerShellLiteral $mavenCommand
    $sharedDataRoot = Join-Path $projectRoot '.data'
    $lines = @(
        "`$env:JAVA_HOME = $javaHomeLiteral"
        "`$env:PATH = $javaBinLiteral + ';' + `$env:PATH"
        "`$env:MAVEN_OPTS = $mavenOptsLiteral"
        switch ($Module) {
            'youtube-service' {
                "`$env:TOOLBOX_YOUTUBE_STORAGEDIRECTORY = $(ConvertTo-PowerShellLiteral (Join-Path $sharedDataRoot 'music'))"
                "`$env:TOOLBOX_YOUTUBE_SETTINGSFILE = $(ConvertTo-PowerShellLiteral (Join-Path (Join-Path $sharedDataRoot 'music') 'youtube-settings.json'))"
            }
            'series-service' {
                "`$env:TOOLBOX_SERIES_STORAGEDIRECTORY = $(ConvertTo-PowerShellLiteral (Join-Path $sharedDataRoot 'series'))"
            }
            'preferences-service' {
                "`$env:TOOLBOX_PREFERENCES_STORAGEDIRECTORY = $(ConvertTo-PowerShellLiteral (Join-Path $sharedDataRoot 'preferences'))"
                "`$env:TOOLBOX_PREFERENCES_LEGACYUSERFILES = $(ConvertTo-PowerShellLiteral ((Join-Path (Join-Path $sharedDataRoot 'music') 'music-player-users.json') + ',' + (Join-Path $sharedDataRoot 'music-player-users.json')))"
                "`$env:TOOLBOX_PREFERENCES_LEGACYTHEMEFILE = $(ConvertTo-PowerShellLiteral (Join-Path (Join-Path $sharedDataRoot 'music') 'music-player-theme.json'))"
            }
        }
        "& $mavenLiteral '-f' $pomLiteral '-s' $settingsLiteral '-Djava.version=27' 'spring-boot:run'"
        "if (`$LASTEXITCODE -ne 0) { Write-Host 'Maven exited with code ' `$LASTEXITCODE -ForegroundColor Red }"
    )

    Start-AtalayaWindow -Name $Name -WorkingDirectory $serviceRoot -CommandLines $lines
}

function Start-AtalayaMusicLibrary {
    $npmPathLiteral = ConvertTo-PowerShellLiteral $npm.Source
    Start-AtalayaWindow -Name 'Music Library (Angular)' `
        -WorkingDirectory $musicRoot `
        -CommandLines @("& $npmPathLiteral 'start'")
}

function Get-AtalayaLanAddresses {
    Get-NetIPConfiguration |
        Where-Object { $_.NetAdapter.Status -eq 'Up' } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -and $_ -notmatch '^169\.254\.' } |
        Sort-Object -Unique
}

function Start-AtalayaStack {
    param([Parameter(Mandatory = $true)][string]$Target)

    switch ($Target) {
        'Youtube' {
            Start-AtalayaService -Module 'youtube-service' -Name 'YouTube Service'
        }
        'Series' {
            Start-AtalayaService -Module 'series-service' -Name 'Series Tracker Service'
        }
        'Preferences' {
            Start-AtalayaService -Module 'preferences-service' -Name 'User Preferences Service'
        }
        'Gateway' {
            Start-AtalayaService -Module 'gateway' -Name 'Gateway'
        }
        'Music' {
            Start-AtalayaMusicLibrary
        }
        'All' {
            Start-AtalayaService -Module 'youtube-service' -Name 'YouTube Service'
            Start-AtalayaService -Module 'series-service' -Name 'Series Tracker Service'
            Start-AtalayaService -Module 'preferences-service' -Name 'User Preferences Service'
            Start-AtalayaService -Module 'gateway' -Name 'Gateway'
            Start-AtalayaMusicLibrary
        }
    }
}

if ($Start -eq 'Menu') {
    Write-Host 'Atalaya local development launcher'
    Write-Host "Java: $JavaHome"
    Write-Host "Maven settings: $MavenSettings"
    Write-Host 'Connect to the required VPN before starting Maven services.'
    Write-Host ''
    Write-Host '1. YouTube Service'
    Write-Host '2. Series Tracker Service'
    Write-Host '3. User Preferences Service'
    Write-Host '4. Gateway'
    Write-Host '5. Music Library (Angular)'
    Write-Host '6. Start all five'
    $choice = Read-Host 'Choose 1-6'

    switch ($choice) {
        '1' { Start-AtalayaStack -Target 'Youtube' }
        '2' { Start-AtalayaStack -Target 'Series' }
        '3' { Start-AtalayaStack -Target 'Preferences' }
        '4' { Start-AtalayaStack -Target 'Gateway' }
        '5' { Start-AtalayaStack -Target 'Music' }
        '6' { Start-AtalayaStack -Target 'All' }
        default { throw "Invalid choice '$choice'. Choose 1, 2, 3, 4, 5, or 6." }
    }
} else {
    Start-AtalayaStack -Target $Start
}

if ($Start -eq 'All' -or ($Start -eq 'Menu' -and $choice -eq '6')) {
    Write-Host ''
    Write-Host 'Gateway:       http://localhost:8080'
    Write-Host 'YouTube API:   http://localhost:8081'
    Write-Host 'Series API:    http://localhost:8082'
    Write-Host 'Preferences:   http://localhost:8083'
    Write-Host 'Music Library: http://localhost:4200'
    $lanAddresses = @(Get-AtalayaLanAddresses)
    foreach ($address in $lanAddresses) {
        Write-Host "Phone access:  http://${address}:4200"
    }
    Write-Host 'Use the address for the network shared with your phone; Windows Firewall may need to allow TCP 4200.'
} elseif ($Start -eq 'Music' -or ($Start -eq 'Menu' -and $choice -eq '5')) {
    Write-Host ''
    Write-Host 'Music Library: http://localhost:4200'
    foreach ($address in @(Get-AtalayaLanAddresses)) {
        Write-Host "Phone access:  http://${address}:4200"
    }
    Write-Host 'Use the address for the network shared with your phone; Windows Firewall may need to allow TCP 4200.'
} elseif ($Start -eq 'Series' -or ($Start -eq 'Menu' -and $choice -eq '2')) {
    Write-Host ''
    Write-Host 'Series API: http://localhost:8082'
} elseif ($Start -eq 'Preferences' -or ($Start -eq 'Menu' -and $choice -eq '3')) {
    Write-Host ''
    Write-Host 'Preferences API: http://localhost:8083'
}
