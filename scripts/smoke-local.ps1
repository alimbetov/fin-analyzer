#requires -Version 7.2
<#
FIN local smoke. Run with: pwsh -NoProfile -File .\scripts\smoke-local.ps1
Uses a fresh, synthetic organization and never deletes or truncates existing data.
#>
[CmdletBinding()]
param(
  [string]$Backend='http://127.0.0.1:8086',
  [string]$Frontend='http://127.0.0.1:5173',
  [switch]$SkipImport,
  [switch]$SkipDocker
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$base=$Backend.TrimEnd('/')
$front=$Frontend.TrimEnd('/')
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runId=[guid]::NewGuid().ToString('N')
$workdir=Join-Path ([IO.Path]::GetTempPath()) ("fin-smoke-"+$runId)
[void](New-Item -ItemType Directory -Force -Path $workdir)
$checks=[Collections.Generic.List[object]]::new()
$org=[guid]::NewGuid().ToString()
$entity=[guid]::NewGuid().ToString()
$scope=[guid]::NewGuid().ToString()
$project=[guid]::NewGuid().ToString()
$instance=''
$revision=0
$failed=$false
$stepName=''

function Assert([bool]$valid,[string]$message) {
  if(-not $valid){throw $message}
}
function Step([string]$name,[scriptblock]$body) {
  $script:stepName=$name
  Write-Host ("[RUN ] "+$name) -ForegroundColor Cyan
  $watch=[Diagnostics.Stopwatch]::StartNew()
  try {
    & $body
    $watch.Stop()
    $checks.Add([pscustomobject]@{name=$name;status='PASS';ms=$watch.ElapsedMilliseconds})
    Write-Host ("[PASS] "+$name) -ForegroundColor Green
  } catch {
    $watch.Stop()
    $checks.Add([pscustomobject]@{name=$name;status='FAIL';ms=$watch.ElapsedMilliseconds;error=$_.Exception.Message})
    throw
  }
}
function GetJson([string]$path) {
  Invoke-RestMethod -Method Get -Uri ($base+$path) -TimeoutSec 20
}
function SendJson([object]$request,[string]$key) {
  $body=ConvertTo-Json -InputObject $request -Depth 10 -Compress
  Invoke-RestMethod -Method Post -Uri ($base+'/api/v1/drafts') -TimeoutSec 30 -ContentType 'application/json' -Headers @{'Idempotency-Key'=$key} -Body ([Text.Encoding]::UTF8.GetBytes($body))
}
function ExpectHttp([scriptblock]$call,[int]$expected,[string]$purpose) {
  $status=0
  try { & $call | Out-Null }
  catch {
    if($null -eq $_.Exception.Response){throw}
    $status=[int]$_.Exception.Response.StatusCode
  }
  Assert ($status -eq $expected) ("$purpose expected HTTP $expected but returned $status")
}
function Upload([string]$url,[string]$file,[string]$reason,[string]$key='') {
  $args=@{
    Uri=($base+$url);Method='Post';TimeoutSec=40
    Form=@{
      file=(Get-Item $file);instanceId=$instance;organizationId=$org
      actor='fin-smoke-local';reason=$reason
    }
  }
  if($key){$args['Headers']=@{'Idempotency-Key'=$key}}
  Invoke-RestMethod @args
}
function EditXlsx([string]$source,[string]$target,[string]$amount) {
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $original=[IO.Compression.ZipFile]::OpenRead($source)
  $output=$null
  $modified=$false
  try {
    $output=[IO.Compression.ZipFile]::Open($target,[IO.Compression.ZipArchiveMode]::Create)
    foreach($entry in $original.Entries){
      $copy=$output.CreateEntry($entry.FullName,[IO.Compression.CompressionLevel]::Optimal)
      $src=$entry.Open()
      $dst=$copy.Open()
      try {
        if($entry.FullName -eq 'xl/worksheets/sheet1.xml'){
          $reader=[IO.StreamReader]::new($src,[Text.Encoding]::UTF8)
          $xml=$reader.ReadToEnd()
          $reader.Dispose()
          $regex='<c\b(?=[^>]*\br="D5")[^>]*(?:/>|>.*?</c>)'
          $match=[regex]::Match($xml,$regex,[Text.RegularExpressions.RegexOptions]::Singleline)
          Assert $match.Success 'XLSX REPORT!D5 is absent (V4 format expected)'
          $replacement='<c r="D5" t="inlineStr"><is><t>'+$amount+'</t></is></c>'
          $xml=$xml.Substring(0,$match.Index)+$replacement+$xml.Substring($match.Index+$match.Length)
          $writer=[IO.StreamWriter]::new($dst,[Text.UTF8Encoding]::new($false),4096,$true)
          $writer.Write($xml)
          $writer.Flush()
          $writer.Dispose()
          $modified=$true
        } else { $src.CopyTo($dst) }
      } finally {
        $dst.Dispose()
        $src.Dispose()
      }
    }
  } finally {
    if($null -ne $output){$output.Dispose()}
    $original.Dispose()
  }
  Assert $modified 'REPORT sheet not updated'
}

try {
  Step '01 PostgreSQL Docker health' {
    if(-not $SkipDocker){
      Push-Location $root
      try {
        $output= & docker compose -f compose.local.yml exec -T postgres pg_isready -U FIN -d fin_form_library 2>&1
        Assert ($LASTEXITCODE -eq 0) ("PostgreSQL not ready: "+($output -join ' '))
      } finally {Pop-Location}
    }
  }
  Step '02 Backend catalog 166 forms / 47 categories' {
    $nav=GetJson '/api/v1/catalog/navigation?locale=ru'
    Assert ($nav.totalForms -eq 166) ("Form count: "+$nav.totalForms)
    $tree=GetJson '/api/v1/catalog/category-tree?locale=ru'
    Assert ($tree.categories.Count -eq 47) ("Category count: "+$tree.categories.Count)
    Assert ($tree.links.Count -eq 389) ("Form/category links: "+$tree.links.Count)
    $f=GetJson '/api/v1/catalog/forms/F02?locale=ru'
    Assert ($f.form.formCode -eq 'F02') 'F02 form contract missing'
    $manual=@($f.fields|Where-Object {$_.inputMode -eq 'MANUAL'})
    Assert ($manual.Count -ge 3) 'F02 manually editable fields missing'
    $curr=GetJson '/api/v1/catalog/currencies?locale=ru'
    Assert (@($curr|Where-Object {$_.code -eq 'KZT'}).Count -eq 1) 'KZT currency missing'
  }
  Step '03 React Vite HTML and /api proxy' {
    $page=Invoke-WebRequest -Uri $front -TimeoutSec 20
    Assert ($page.StatusCode -eq 200) 'React HTML unavailable'
    Assert ($page.Content -match 'FIN') 'Unexpected frontend HTML'
    $proxy=Invoke-RestMethod -Uri ($front+'/api/v1/catalog/navigation?locale=ru') -TimeoutSec 20
    Assert ($proxy.totalForms -eq 166) 'Vite REST proxy broken'
  }

  $first=[ordered]@{
    instanceId=$null;organizationId=$org;economicEntityId=$entity
    businessScopeId=$scope;projectId=$project;formCode='F02'
    periodType='M';periodCode='2026-11';currency='KZT'
    coverage='PERIOD_ONLY';reportingBasis='MANAGEMENT';dataSpace='SYNTHETIC'
    expectedRevision=0
    values=@{netRevenue='125000.50';cogs='70000';operatingExpenses='1000'}
    rows=@();createdBy='fin-smoke-local';changeReason='smoke initial'
  }
  $firstKey="smoke-new-$runId"
  Step '04 Create F02 revision 1 / readback' {
    $receipt=SendJson $first $firstKey
    Assert ($receipt.revisionNo -eq 1) 'New revision is not 1'
    Assert ($receipt.contentSha256 -match '^[0-9a-f]{64}$') 'Invalid content SHA256'
    $script:instance=[string]$receipt.instanceId
    $script:revision=1
    $snapshot=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
    Assert ($snapshot.revisionNo -eq 1) 'Revision 1 readback failed'
    Assert ($snapshot.sha256 -eq $receipt.contentSha256) 'SHA mismatch after write'
    Assert ($snapshot.values.netRevenue -eq '125000.5') 'Decimal normalization failed'
    Assert ($snapshot.projectId -eq $project) 'Project binding lost'
  }
  Step '05 Idempotent request replay' {
    $replay=SendJson $first $firstKey
    Assert ($replay.instanceId -eq $instance) 'Replay created another instance'
    Assert ($replay.revisionNo -eq 1) 'Replay created another revision'
    Assert ($replay.result -eq 'IDEMPOTENT_REPLAY') 'Replay not acknowledged'
  }

  $second=@{}
  foreach($key in $first.Keys){$second[$key]=$first[$key]}
  $second.instanceId=$instance
  $second.expectedRevision=1
  $second.changeReason='smoke updated'
  $second.values=@{netRevenue='130000.50';cogs='70000';operatingExpenses='1000'}
  Step '06 Update revision 2 / immutable history' {
    $receipt=SendJson $second "smoke-update-$runId"
    Assert ($receipt.revisionNo -eq 2) 'Expected revision 2'
    $script:revision=2
    $old=GetJson ("/api/v1/drafts/$instance/revisions/1?organizationId=$org")
    $current=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
    Assert ($old.values.netRevenue -eq '125000.5') 'Revision 1 was modified'
    Assert ($current.values.netRevenue -eq '130000.5') 'Revision 2 value mismatch'
    Assert ($current.sha256 -eq $receipt.contentSha256) 'Revision 2 SHA mismatch'
  }
  Step '07 Stale expectedRevision rejected (409)' {
    $stale=@{}
    foreach($key in $second.Keys){$stale[$key]=$second[$key]}
    $stale.values=@{netRevenue='190000';cogs='70000';operatingExpenses='1000'}
    ExpectHttp {SendJson $stale "smoke-stale-$runId"} 409 'Stale save'
    $view=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
    Assert ($view.revisionNo -eq 2) 'Stale write changed revision'
  }
  Step '08 XLSX V4 export and filename' {
    $exportFile=Join-Path $workdir 'source.xlsx'
    $uri="$base/api/v1/drafts/$instance/export.xlsx?organizationId=$org&styleCode=EXCEL&locale=ru"
    $headers=$null
    Invoke-WebRequest -Uri $uri -OutFile $exportFile -ResponseHeadersVariable headers -TimeoutSec 30 | Out-Null
    $disp=[string]$headers['Content-Disposition']
    Assert ($disp -match 'FIN_F02_M-2026-11_KZT_EXCEL_ru_') 'Wrong XLSX accounting context'
    Assert ($disp -match '_r0002_[0-9a-f]{12}\.xlsx') 'XLSX revision/hash filename mismatch'
    Assert ((Get-Item $exportFile).Length -gt 1000) 'XLSX export empty'
    $script:sourceFile=$exportFile
  }

  if(-not $SkipImport){
    Step '09 Modify XLSX REPORT D5 (no Excel required)' {
      $script:editedFile=Join-Path $workdir 'changed.xlsx'
      EditXlsx $script:sourceFile $script:editedFile '140000.50'
      Assert ((Get-Item $script:editedFile).Length -gt 1000) 'Edited XLSX empty'
    }
    Step '10 Register XLSX import profile' {
      $version=GetJson ("/api/v1/uat-helper/drafts/$instance/version?organizationId=$org")
      Assert ([bool]$version.formVersionId) 'Form version UUID missing'
      $script:profile=Invoke-RestMethod -Method Post -Uri ($base+'/api/v1/import-profiles') -TimeoutSec 30 -ContentType 'application/json' -Body (ConvertTo-Json -InputObject @{formVersionId=$version.formVersionId})
      Assert ([bool]$script:profile.id) 'Profile UUID missing'
    }
    Step '11 Preview XLSX must not save a revision' {
      $script:ticket=Upload ("/api/v1/import-profiles/$($script:profile.id)/preview") $script:editedFile 'smoke preview'
      Assert ($ticket.expectedRevision -eq 2) 'Preview expects wrong revision'
      Assert ($ticket.organizationId -eq $org) 'Preview organization mismatch'
      Assert ($ticket.instanceId -eq $instance) 'Preview instance mismatch'
      $sha=(Get-FileHash $script:editedFile -Algorithm SHA256).Hash.ToLowerInvariant()
      Assert ($ticket.uploadSha256 -eq $sha) 'Preview upload hash differs'
      Assert ($ticket.values.netRevenue -eq '140000.5') 'Preview amount mismatch'
      $current=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
      Assert ($current.revisionNo -eq 2) 'Preview wrote a financial revision'
    }
    Step '12 Commit XLSX revision 3 / verify SHA and history' {
      $receipt=Upload ("/api/v1/import-profiles/previews/$($script:ticket.previewId)/commit") $script:editedFile 'smoke commit' "smoke-commit-$runId"
      Assert ($receipt.revisionNo -eq 3) 'Expected revision 3 from Commit'
      $script:revision=3
      $now=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
      Assert ($now.revisionNo -eq 3) 'Commit readback revision wrong'
      Assert ($now.sha256 -eq $receipt.contentSha256) 'Commit SHA differs'
      Assert ($now.values.netRevenue -eq '140000.5') 'Imported value not stored'
      $old=GetJson ("/api/v1/drafts/$instance/revisions/2?organizationId=$org")
      Assert ($old.values.netRevenue -eq '130000.5') 'Historical revision 2 was changed'
    }
    Step '13 Consumed preview cannot be committed twice (409)' {
      ExpectHttp {
        Upload ("/api/v1/import-profiles/previews/$($script:ticket.previewId)/commit") $script:editedFile 'duplicate' "smoke-replay-$runId"
      } 409 'Consumed import preview'
      $now=GetJson ("/api/v1/drafts/$instance?organizationId=$org")
      Assert ($now.revisionNo -eq 3) 'Replay created revision 4'
    }
  }
  Write-Host ("FIN SMOKE PASS: "+$checks.Count+" checks") -ForegroundColor Green
  Write-Host ("Instance: "+$instance)
  Write-Host ("Revision: "+$revision)
} catch {
  $failed=$true
  Write-Host ("FIN SMOKE FAIL: "+$stepName) -ForegroundColor Red
  Write-Host $_.Exception.Message -ForegroundColor Red
} finally {
  $result=[ordered]@{
    runId=$runId;timestamp=(Get-Date).ToUniversalTime().ToString('o')
    organizationId=$org;instanceId=$instance;lastRevision=$revision
    backend=$base;frontend=$front;checks=@($checks.ToArray())
  }
  $reportFile=Join-Path $workdir 'result.json'
  $result|ConvertTo-Json -Depth 6|Set-Content -Path $reportFile -Encoding UTF8
  Write-Host ("Smoke report: "+$reportFile)
}
if($failed){exit 1}
exit 0
