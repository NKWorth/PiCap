# Link this repo to GitHub and push the main branch.
#
# Usage:
#   .\scripts\github-setup.ps1 -GitHubUsername YOUR_GITHUB_USERNAME
#   .\scripts\github-setup.ps1 -GitHubUsername YOUR_GITHUB_USERNAME -RepoName PiCap
#
# Create the empty repository on GitHub first:
#   https://github.com/new

param(
    [Parameter(Mandatory = $true)]
    [string]$GitHubUsername,

    [string]$RepoName = "PiCap"
)

$remote = "git@github.com:${GitHubUsername}/${RepoName}.git"

$existing = git remote 2>$null
if ($existing -contains "origin") {
    Write-Host "Remote 'origin' already exists:"
    git remote -v
    exit 1
}

git remote add origin $remote
git push -u origin main

Write-Host "Done. Remote origin is $remote"
