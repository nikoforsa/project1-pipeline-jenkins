def PUBLISH = false
def name_project = ''
def major = ''
def minor = ''
def patch = ''
def GIT_USERNAME = ''
def GIT_PASSWORD = ''

def call(args, publish) {
    //properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')), pipelineTriggers([])])
    def agents = []
    def props = [:]
    def autotests = [:]
    nodeMap = [:]
    PUBLISH = publish
    gitsshuser = ''
    gitsshkey = ''

    if (args in HashMap) {
        args.each { key, value ->
            if (key == 'agents') {
                agents = value
            } else if (key == 'autotests') {
                autotests = value
            } else {
                props[key] = value
            }
        }
    }

// println(agents);
// println(props);
// println(autotests);

    try {
        cleanWs()

        run(agents, props, autotests)

        safeCleanWs()

    } 
    catch (Exception err) 
    { 
        println "Exception: $err"
        
        if (currentBuild.rawBuild.getActions(jenkins.model.InterruptedBuildAction.class).isEmpty()) 
        {
            currentBuild.result = 'FAILURE'
        } else 
        {
            currentBuild.result = 'ABORTED'
        }

    }

    if (!isGitlabMergeRequest()) {
        unstash 'conandata'
        def filename = 'conandata.yml'
        def conf = readYaml file: filename        
        props['publication_path']='//'+conf.storage.server+'/'+ conf.storage.publication_path+'/'+conf.name_project+'/'+conf.project_tag_version+'/'
        echo "Notify ${props}"
        notifyBuildFinished(props)
    } else {
        if (currentBuild.result == 'FAILURE') {
        
            updateGitlabCommitStatus state:  'failed'
        } else if (currentBuild.result == 'ABORTED') {
            updateGitlabCommitStatus state: 'canceled'
        } else {
            updateGitlabCommitStatus state:  'success'
        }
    }
}

def safeCleanWs() {
    try {
        cleanWs()
    } catch (Exception err) {
        println "[Error]: Can't clean the workspace: $err"
    }
}

def mk_dummy_dir(String name) {
    dir(name) {
        writeFile file:'dummy', text:''
    }
}

def buildTask(independent, params, pr_publish, autotests) {
    return buildTask(independent, params, pr_publish, null, autotests)
}

def buildTask(independent, params, pr_publish, props, autotests) {
    if (independent) {
        cleanWs()
        checkout scm
    }

    if (isGitlabMergeRequest()) {
        mk_dummy_dir(pr_publish)
    }
    unstash 'build_properties'
    unstash 'conandata'
    execCompatibleCmds(['sjph_build ' + params])

    if (isGitlabMergeRequest() && autotests) {
        archiveArtifacts(
            artifacts: autotests["artifacts"],
            allowEmptyArchive: true
            )
    }

    if (props?.'label') {
        if (is_pvs_requested(props['label'])) {
            attach_pvs_reports()
        }
    }

    if (independent) {
        safeCleanWs()
    }
}

def buildTaskDockerWin(independent, params, pr_publish, props, type_build ) {
    if (independent) {
        cleanWs()
        checkout scm
        println "---------------------1.Docker-------------------"
    }
    
    //unstash 'build_properties'
    unstash 'conandata'

    withCredentials([[$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE']])
    {
        if (isGitlabMergeRequest()) {
    //    mk_dummy_dir(pr_publish)
            PUBLISH = false
        }
        script{
            println "---------------------4.Docker-------------------"
            //bat 'echo %PATH%'
            //println('username= $gitsshuser password= ${KEY_FILE} path= ${gitkeypath}')
            //sh 'pwd'
            //unstash 'sshk'
            bat 'powershell Get-ChildItem Env:'
            writeFile file: 'id_rsa', text: readFile(KEY_FILE)
            if (!KEY_FILE.isEmpty()){
                gitsshkey = 'True'
            }
        }
    }
    
    def MYAPP_IMAGE = ""
    echo "NODE_NAME: " + NODE_NAME
    println('Inside node docker Package: ' + NODE_LABELS )
    
    // Parse the YAML.
    // def filename = 'conandata.yml'
    // def conf = readYaml file: filename
    // def (name_project, major, minor, patch ) = props['label'].tokenize( '.' )
    // conf.pkgs.control.Version = major+'.'+minor+'.'+patch
    // bat "del $filename"
    // //sh "rm $filename"
    
    // println(conf.pkgs.control.Version)
    // writeYaml file: filename, data: conf
    // conf = readYaml file: filename
    // println(conf)
    switch(params) {
        case "msvc19-probe":
            MYAPP_IMAGE="probe-compile-16"
            //MYAPP_IMAGE="build-astra"
            //"build-astra-gcc8"
            break
        case "msvc19-release":
            MYAPP_IMAGE="msvc19-release"
            //MYAPP_IMAGE="build-ubuntu"
            //"build-ubuntu-gcc9"
            break
        default:
            MYAPP_IMAGE=params
            break
    }
    def app
    def buildArgs = """ \
        --build-arg CONAN_LOGIN_USERNAME=$CONAN_LOGIN_USERNAME --build-arg CONAN_PASSWORD=$CONAN_PASSWORD \
        --build-arg BUILDER_USER=$BUILDER_USER --build-arg BUILDER_PASSWORD=$BUILDER_PASSWORD \
        --build-arg GIT_USERNAME=$GIT_USERNAME --build-arg GIT_PASSWORD=$GIT_PASSWORD \
        --build-arg NAME_PROJECT=$name_project --build-arg VERSION_PROJECT=$major.$minor.$patch \
        --build-arg TYPE_BUILD=$type_build --build-arg PUBLISH=$PUBLISH \
        --build-arg STF_BUILD_IMAGE_PATH="/home/user/package/" \
        --build-arg HOME_DIRECTORY="/home/user/" \
        --build-arg PACKAGE_DIRECTORY="/home/user/package/" \
        --build-arg GITSSH=$gitsshuser --build-arg GITSSHKEY=$gitsshkey \
        --build-arg MYAPP_IMAGE=$MYAPP_IMAGE \
        -f Dockerfile_build_win . """
        //-f Dockerfile_build_package -o out . """
    //println(buildArgs)                               
    echo "Building docker images WINDOWS node"
    //def tags = MYAPP_IMAGE+ '_' +type_build.toLowerCase()+ '_' +params
    def tags = name_project + '_' + type_build.toLowerCase() + '_' + params
    //docker.build("MYAPP_IMAGE:${env.BUILD_ID}",
    app = docker.build(tags, buildArgs)
    //массив нод для последующей очистки 
    nodeMap.put NODE_NAME,tags

    echo "---------------------5.remove build image-------------------"
    bat "docker rmi -f ${tags}"
  
    if (independent) {
        safeCleanWs()
    }
}

def buildTaskDockerLinux(independent, params, pr_publish, props, type_build ) {
    if (independent) {
        cleanWs()
        checkout scm
        println "---------------------1.Docker-------------------"
    }
 
    //unstash 'build_properties'
    unstash 'conandata'

    withCredentials([[$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE']])
    {
        if (isGitlabMergeRequest()) {
    //     mk_dummy_dir(pr_publish)
            PUBLISH = false
        }
    //     //execCompatibleCmds([cmd])
        script{
            gitsshuser = USERNAME
            sh 'cp -i ${KEY_FILE} ./id_rsa'
            if (!KEY_FILE.isEmpty()){
                gitsshkey = 'True'
            }
        }
    }

    def MYAPP_IMAGE = ""
    echo "NODE_NAME: " + NODE_NAME
    println('Inside node docker Package: ' + NODE_LABELS )
    
    // Parse the YAML.
    // def filename = 'conandata.yml'
    // def conf = readYaml file: filename
    // def (name_project, major, minor, patch ) = props['label'].tokenize( '.' )
    // conf.pkgs.control.Version = major+'.'+minor+'.'+patch
    // //bat "del $filename"
    // sh "rm $filename"
    
    // println(conf.pkgs.control.Version)
    // writeYaml file: filename, data: conf
    // conf = readYaml file: filename
    //println(conf)
    switch(params) {
        case "gcc8":
            MYAPP_IMAGE="build-astra-gtk"
            //MYAPP_IMAGE="build-astra"
            //"build-astra-gcc8"
            break
        case "gcc9":
            MYAPP_IMAGE="build-ubuntu-gtk"
            //MYAPP_IMAGE="build-ubuntu"
            //"build-ubuntu-gcc9"
            break
        default:
            MYAPP_IMAGE=params
            break
    }
    def app
    def buildArgs = """ \
        --build-arg CONAN_LOGIN_USERNAME=$CONAN_LOGIN_USERNAME --build-arg CONAN_PASSWORD=$CONAN_PASSWORD \
        --build-arg BUILDER_USER=$BUILDER_USER --build-arg BUILDER_PASSWORD=$BUILDER_PASSWORD \
        --build-arg GIT_USERNAME=$GIT_USERNAME --build-arg GIT_PASSWORD=$GIT_PASSWORD \
        --build-arg NAME_PROJECT=$name_project --build-arg VERSION_PROJECT=$major.$minor.$patch \
        --build-arg TYPE_BUILD=$type_build --build-arg PUBLISH=$PUBLISH \
        --build-arg STF_BUILD_IMAGE_PATH="/home/user/package/" \
        --build-arg HOME_DIRECTORY="/home/user/" \
        --build-arg PACKAGE_DIRECTORY="/home/user/package/" \
        --build-arg GITSSH=$gitsshuser --build-arg GITSSHKEY=$gitsshkey \
        --build-arg MYAPP_IMAGE=$MYAPP_IMAGE \
        -f Dockerfile_build_linux . """
        //-f Dockerfile_build_package -o out . """
    //println(buildArgs)                               
    echo "Building docker images Linux node"
    //def tags = MYAPP_IMAGE+ '_' +type_build.toLowerCase()+ '_' +params
    def tags = name_project + '_' + type_build.toLowerCase() + '_' + params
    //docker.build("MYAPP_IMAGE:${env.BUILD_ID}",
    app = docker.build(tags, buildArgs)
    //массив нод для последующей очистки 
    nodeMap.put NODE_NAME,tags
    // if (isGitlabMergeRequest() && autotests) {
    //     archiveArtifacts(
    //         artifacts: autotests["artifacts"],
    //         allowEmptyArchive: true
    //         )
    // }
    echo "---------------------5.remove build image-------------------"
    sh "docker rmi -f ${tags}"
    
    if (independent) {
        safeCleanWs()
    }
}

def run(agents, props, autotests) {
    def pr_publish = '__publish__'
    def images = []
    def workspace = WORKSPACE
    //Дефолтная установка тэга с публикацией в гит если это не мерд реквест 
    def SetTag = 'save'
    def tag = ''
    def tag_out = ''
    List tag_parts, s

    withCredentials([
        [$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE'],
        usernamePassword(credentialsId: 'user-gitlab', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')])
    {
        stage('Prepare-tag'){
            //echo ${env.WORKSPACE}
            //cleanWs()
            checkout scm
            workspace = WORKSPACE
            
            println env.GITLAB_CRED_ID          

            stash(name: 'conandata', includes: 'conandata.yml')

            withCredentials([
                    [$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE'],
                    usernamePassword(credentialsId: 'CONAN_USERNAME_PASS', passwordVariable: 'CONAN_PASSWORD', usernameVariable: 'CONAN_LOGIN_USERNAME'),
                    usernamePassword(credentialsId: 'SMB_ACCESS_CREDENTIALS', passwordVariable: 'BUILDER_PASSWORD', usernameVariable: 'BUILDER_USER')])
            {
                if (isGitlabMergeRequest()) 
                {
                    updateGitlabCommitStatus state: 'pending'
                    //если мерж реквест вычисляем тэг и проверяем сборку проекта
                    SetTag = 'tag'
                    echo "Current SetTag is ${SetTag}"
                }else{
                    // props['product']="Hello Project"
                    // props['label']=$name_project.$major.$minor.$patch
                    // props['next_label']="hello.1.1.12"
                    echo "Current notify is ${props}"
                    //notifyBuildRun(props)
                }
                echo "Current workspace is ${env.WORKSPACE}"
                // the current Jenkins instances will support the short syntax, too:
                echo "Current workspace is $WORKSPACE"
                dir('tag'){
                    //скачиваем скрипт тегирования из репозитория и стэшим
                    git credentialsId: env.GITLAB_CRED_ID,
                        url: 'git@git.com:project1/bld-helpers.git'
                    stash(name: 'tag_project', includes: 'tag_project.py')
                }
                dir(workspace){
                    //возвращаемся в воркспейс проекта, тегируем проект, стешим conandata.yml
                    unstash 'tag_project'
                    // if (isUnix()){
                    //     sh 'tree -daL 2'
                    // }else{
                    //     bat 'tree'
                    // }
                    //unstash 'conandata'
                    echo "Current2 SetTag is ${SetTag}"
                    if (isUnix()){    
                        tag_out = sh (script: 'python tag_project.py --'+SetTag, returnStdout: true) //.trim()
                    }
                    else{
                        tag_out = bat (script: 'python tag_project.py --'+SetTag, returnStdout: true) //.trim()
                    }
                    
                    echo "Git tag_out: ${tag_out}"
                    try { tag = tag_out.tokenize('\n') } catch(e) {}
                    
                    echo "Git tag: ${tag[0]}"
                    echo "Git tag: ${tag[1]}"
                    echo "Git tag: ${tag[2]}"
                    tag_parts = tag[2].tokenize(".")
                    buildDescription tag[2]
                    echo "list: ${tag_parts}"
                    name_project = tag_parts[0]
                    major = tag_parts[1].replace('\r','')
                    minor = tag_parts[2].replace('\r','')
                    patch = tag_parts[3].replace('\r','')
                    if (!isGitlabMergeRequest()) 
                    {
                        props['product']=name_project
                        props['label']=name_project+ '.' +major+ '.' +minor+ '.' +patch
                        props['next_label']=name_project+ '.' +major+ '.' +minor+ '.' +(patch.toInteger()+1).toString()
                        echo "Current notify is ${props}"
                        notifyBuildRun(props)
                    }
                    //echo "list: ${tag_parts} naming parts: ${name_project} - ${major} - ${minor} - ${patch}"
                    //def (name, major, minor, patch, p1 ) = tag.tokenize('.')
                    //.replace("\n","")
                    //stash(name: 'conandata', includes: 'conandata.yml')
                    if (isUnix()){
                        sh 'cat conandata.yml'
                    }else{
                        bat 'type conandata.yml'
                    }
                    stash(name: 'conandata', includes: 'conandata.yml')
                }
            }
        }
        
        stage('Build') 
        {            
            withCredentials([
                usernamePassword(credentialsId: 'CONAN_USERNAME_PASS', passwordVariable: 'CONAN_PASSWORD', usernameVariable: 'CONAN_LOGIN_USERNAME'),
                [$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE'],
                usernamePassword(credentialsId: 'SMB_ACCESS_CREDENTIALS', passwordVariable: 'BUILDER_PASSWORD', usernameVariable: 'BUILDER_USER')
            ]){
                //usernamePassword(credentialsId: 'user-gitlab', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME'),
                if (isGitlabMergeRequest()) 
                {
                    updateGitlabCommitStatus state: 'running'
                }
                if (agents) {
                    def builders = [:]
                    def n = 0;
                    def base = 'agent #'

                    agents.each { agent ->
                        def label = agent[0]
                        def cfg = agent[1]
                        //++n;
                        def _props = [:]
                        //def name = base + n + ' (' + label + ')';
                        if (label == 'windows-docker') {
                            cfg.each { compilers, configvalue ->
                                for ( type_build in configvalue ){
                                    ++n;
                                    def name_build = base + n + ' (' + label + '-' + compilers +  '-' + type_build + ')';
                                    def type_b = type_build
                                    builders[name_build] = {
                                        node(label) {
                                            echo "Running ${env.BUILD_ID} on ${env.JENKINS_URL}"
                                            _props['label'] = label
                                            println("RUN WINDOWS NODE") 
                                            buildTaskDockerWin(true, compilers, pr_publish, props, type_b)
                                        }
                                    }
                                }
                            }
                        }
                        //if (label == 'astra' || label == 'ubuntu') {
                        if (label == 'linux-docker' ) {
                            cfg.each { compilers, configvalue ->
                                for ( type_build in configvalue ){
                                    ++n;
                                    def name_build = base + n + ' (' + label + '-' + compilers +  '-' + type_build + ')';
                                    def type_b = type_build
                                    builders[name_build] = {
                                        node(label) {
                                            echo "Running ${env.BUILD_ID} on ${env.JENKINS_URL}"
                                            _props['label'] = label
                                            println("RUN LINUX NODE "+'-----'+name_build +'-----'+type_b)
                                            buildTaskDockerLinux(true, compilers, pr_publish, props, type_b )
                                        }
                                    }
                                }
                            }
                        }                        
                    }
                    if (isGitlabMergeRequest()) {
                        builders.failFast = false
                    }
                    else {
                        builders.failFast = true
                    } 
                    
                   parallel builders

                } else {
                    buildTask(false, '', pr_publish, autotests)
                }
               
            }
        }

        stage('Finalize-Hashsums') {
            if (!isGitlabMergeRequest()) {
                buildHashSumsDockerLinux()
            } else {
                echo "Skip for a merge requests"
                updateGitlabCommitStatus state: 'success'
            }
            safeCleanWs()
        }
        stage('Clean Dangling Image & Containers') {
            nodeMap.each { 
                node(it.key) {
                    //script {
                        //sh 'docker images -q -f dangling=true | xargs --no-run-if-empty docker rmi'
                        //images=$(docker images -f dangling=true -q); if [[ ${images} ]]; then docker rmi --force ${images}; fi
                        if (isUnix()){
                                echo "$it.value"
                            //stage('Clean Dangling Containers') {
                                sh 'docker ps -q -f status=exited | xargs --no-run-if-empty docker rm'
                            //}
                            //stage('Clean Dangling Images') {
                                sh 'docker images -q -f dangling=true | xargs --no-run-if-empty docker rmi'
                            //}
                        } else {
                                echo "$it.value"
                            //stage('Clean Dangling Images windows') {
                            //bat 'docker rmi $(docker images -q -f dangling=true 2> nul)2> nul'
                            //foreach ($cnt in $(docker ps -aq)) {docker rm $cnt;Write-Host "$cnt deleted"}
                                powershell 'foreach ($cnt in $(docker ps -aq)) {docker rm $cnt;Write-Host "$cnt deleted"}'
                                // bat '''echo off & for /f "delims=" %%A in (\'docker images -f "dangling=true" -q \') do docker rmi %%A & echo on
                                //     '''   
                            //}
                        }
                    //}                    
                }    
            }
        }
    }
}

def buildHashSumsDockerLinux() {
    def n = 0;
    def base = 'agent #'              
    //def name_build = base + n + ' (' + label + ')';
    def label = 'linux' 
    node(label) {
        withCredentials([
            [$class: 'SSHUserPrivateKeyBinding', credentialsId: env.GITLAB_CRED_ID, usernameVariable: 'USERNAME', passphraseVariable: 'KEY_PASSPHRASE', keyFileVariable: 'KEY_FILE'],
            usernamePassword(credentialsId: 'CONAN_USERNAME_PASS', passwordVariable: 'CONAN_PASSWORD', usernameVariable: 'CONAN_LOGIN_USERNAME'),
            usernamePassword(credentialsId: 'SMB_ACCESS_CREDENTIALS', passwordVariable: 'BUILDER_PASSWORD', usernameVariable: 'BUILDER_USER')])
        {
            git credentialsId: env.GITLAB_CRED_ID,
                url: 'git@git.com:project1/bld-smb.git'

            unstash 'conandata'
            script{
                gitsshuser = USERNAME
                if (!KEY_FILE.isEmpty()){
                    gitsshkey = 'True'
                }
                sh 'cp -i ${KEY_FILE} ./id_rsa'
            }

        println("RUN LINUX NODE "+' hashmodule')
        def MYAPP_IMAGE = ""
        echo "NODE_NAME: " + NODE_NAME
        println('Inside node docker Package: ' + NODE_LABELS )
        
        // Parse the YAML.
        // def filename = 'conandata.yml'
        // def conf = readYaml file: filename
        // def name_project = conf.pkgs.control.Package
        // println(conf.pkgs.control.Package)
        // println(name_project)
        // def (major, minor, patch ) = conf.pkgs.control.Version.tokenize( '.' )
        // //conf.pkgs.control.Version = major+'.'+minor+'.'+patch
        // //bat "del $filename"
        // sh "rm $filename"
        // writeYaml file: filename, data: conf
        // conf = readYaml file: filename
        // println(conf)
        switch(params) {
            case "gcc8":
                MYAPP_IMAGE="build-astra-gtk"
                //MYAPP_IMAGE="build-astra"
                //"build-astra-gcc8"
                break
            case "gcc9":
                MYAPP_IMAGE="build-ubuntu-gtk"
                //MYAPP_IMAGE="build-ubuntu"
                //"build-ubuntu-gcc9"
                break
            default:
                MYAPP_IMAGE="build-ubuntu-gtk"
                break
        }
        def app
        def buildArgs = """ \
            --build-arg CONAN_LOGIN_USERNAME=$CONAN_LOGIN_USERNAME --build-arg CONAN_PASSWORD=$CONAN_PASSWORD \
            --build-arg BUILDER_USER=$BUILDER_USER --build-arg BUILDER_PASSWORD=$BUILDER_PASSWORD \
            --build-arg GIT_USERNAME=$GIT_USERNAME --build-arg GIT_PASSWORD=$GIT_PASSWORD \
            --build-arg NAME_PROJECT=$name_project --build-arg VERSION_PROJECT=$major.$minor.$patch \
            --build-arg TYPE_BUILD="" --build-arg PUBLISH=$PUBLISH \
            --build-arg STF_BUILD_IMAGE_PATH="/home/user/package/" \
            --build-arg HOME_DIRECTORY="/home/user/" \
            --build-arg PACKAGE_DIRECTORY="/home/user/package/" \
            --build-arg GITSSH=$gitsshuser --build-arg GITSSHKEY=$gitsshkey \
            --build-arg MYAPP_IMAGE=$MYAPP_IMAGE \
            -f Dockerfile_hashsums . """
            //-f Dockerfile_build_package -o out . """
        //println(buildArgs)                               
        echo "Building docker images Linux node"
        //def tags = MYAPP_IMAGE+ '_' +type_build.toLowerCase()+ '_' +params
        def tags = name_project+ '_' + 'hashsums'
        //docker.build("MYAPP_IMAGE:${env.BUILD_ID}",
        app = docker.build(tags, buildArgs)
        //массив нод для последующей очистки 
        nodeMap.put NODE_NAME,tags
        echo "---------------------5.remove build image-------------------"
        sh "docker rmi -f ${tags}"
        }
    }
}
