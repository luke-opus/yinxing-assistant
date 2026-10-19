param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$RepositoryName = 'xadskip',
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$Collaborators
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
foreach ($account in $Collaborators) {
    if ($account -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}$') { throw "Invalid GitHub username: $account" }
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install official GitHub CLI and run gh auth login first.' }
gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) { throw 'GitHub login required. Do not paste a password or token into chat.' }
$owner = gh api user --jq '.login'
if ($LASTEXITCODE -ne 0 -or -not $owner) { throw 'Cannot determine authenticated account.' }
$fullName = "$owner/$RepositoryName"
$expectedUsers = @($owner) + @($Collaborators | Select-Object -Unique)
$branch = git branch --show-current
if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') { throw 'Run from the main branch after reviewing your commit.' }
$status = git status --porcelain
if ($LASTEXITCODE -ne 0 -or $status) { throw 'Resolve local changes before publishing.' }

# Personal repositories avoid implicit organization-owner access.
$metadataText = gh api "repos/$fullName" 2>$null
if ($LASTEXITCODE -ne 0) {
    gh repo create $fullName --private --description 'XAdSkip Android development repository'
    if ($LASTEXITCODE -ne 0) { throw 'Private repository creation failed; nothing was pushed.' }
    $metadataText = gh api "repos/$fullName"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify new repository.' }
}
$metadata = $metadataText | ConvertFrom-Json
if (-not $metadata.private -or $metadata.owner.login -ne $owner -or $metadata.owner.type -ne 'User') {
    throw 'Target must be PRIVATE and owned by the authenticated personal account.'
}
function Assert-ExpectedAccess {
    $members = @(gh api "repos/$fullName/collaborators" --paginate --jq '.[].login')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify collaborators.' }
    $pending = @(gh api "repos/$fullName/invitations" --paginate --jq '.[].invitee.login')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify pending invitations.' }
    foreach ($account in @($members) + @($pending)) {
        if ($account -and $account -notin $expectedUsers) { throw "Unexpected existing access: $account" }
    }
}
Assert-ExpectedAccess
$existingRemote = git remote get-url origin 2>$null
$originMissing = $LASTEXITCODE -ne 0
$expectedRemote = "https://github.com/$fullName.git"
if (-not $originMissing -and $existingRemote -ne $expectedRemote) { throw 'Existing origin differs from requested destination.' }
if ($originMissing) {
    git remote add origin $expectedRemote
    if ($LASTEXITCODE -ne 0) { throw 'Cannot add origin.' }
}
gh auth setup-git --hostname github.com
if ($LASTEXITCODE -ne 0) { throw 'Git authentication setup failed.' }
git push -u origin HEAD:main
if ($LASTEXITCODE -ne 0) { throw 'Push failed; this script never force-pushes.' }
foreach ($account in $Collaborators | Select-Object -Unique) {
    if ($account -eq $owner) { continue }
    gh api --method PUT "repos/$fullName/collaborators/$account" -f permission=push --silent
    if ($LASTEXITCODE -ne 0) { throw "Could not invite $account; inspect repository settings." }
}
Assert-ExpectedAccess
$private = gh api "repos/$fullName" --jq '.private'
if ($LASTEXITCODE -ne 0 -or $private -ne 'true') { throw 'Final private verification failed.' }
$remoteSha = gh api "repos/$fullName/commits/main" --jq '.sha'
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify remote commit.' }
$localSha = git rev-parse HEAD
if ($remoteSha -ne $localSha) { throw 'Remote commit differs from local HEAD.' }
Write-Output "Verified private repository: https://github.com/$fullName"
Write-Output "Verified commit: $remoteSha"
gh api "repos/$fullName/collaborators" --paginate --jq '.[].login'
gh api "repos/$fullName/invitations" --paginate --jq '.[].invitee.login'
Write-Output 'Invitations must be accepted by recipients. Account-level GitHub App access is managed separately.'
